package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.babblelang.effects.Effect;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Parsed representation, parser, hierarchy lookup, and canonical-order checker for BIP019 axioms
 * ({@code docs/design/bip019_refactoring-axioms.md} §3).
 */
final class AxiomData {

    enum MemberKind {
        INSTANCE,
        STATIC,
        ANY
    }

    /** Canonical order of class words in {@code class [words...] ( ... )}. */
    static final List<String> CLASS_WORD_ORDER = List.of("exact", "nullmarked", "immutable");

    /** Canonical order of effect/member words in {@code fn [words...] ...}. */
    static final List<String> EFFECT_WORD_ORDER = List.of(
            "io", "volatile", "pure", "mut", "shared", "nothrow",
            "idempotent", "commutative", "associative", "monotone", "injective",
            "view", "shallow", "fresh", "foldable", "unique"
    );

    /** One member axiom: a method, constructor ({@code new}), or wildcard ({@code *}). */
    record MemberAxiom(
            Class<?> ownerClass,
            String name,
            MemberKind kind,
            boolean exact,
            boolean hasSignature,
            List<String> paramDescriptors,
            String returnDescriptor,
            JavaNullability.Nullness[] paramNullness,
            JavaNullability.Nullness returnNullness,
            EffectSet effects,
            boolean mutReceiver,
            boolean borrowReceiver,
            boolean sharedReceiver,
            boolean[] mutParams,
            boolean[] borrowParams,
            boolean[] uniqueParams,
            boolean[] sharedParams,
            boolean view,
            boolean shallow,
            boolean fresh,
            boolean foldable,
            boolean explicitNothrow,
            boolean readsReceiver,
            boolean[] readParams,
            boolean curated) {

        boolean isWildcard() {
            return "*".equals(name);
        }

        boolean isConstructor() {
            return "new".equals(name);
        }

        /** Whether this axiom matches the exact reflected {@link Executable}. */
        boolean matches(Executable exec) {
            if (exec instanceof Constructor<?> ctor) {
                if (ctor.getDeclaringClass() != ownerClass) {
                    return false;
                }
                if (!"new".equals(name) && !("*".equals(name) && kind == MemberKind.STATIC)) {
                    return false;
                }
                return !hasSignature || matchesParams(ctor.getParameterTypes());
            }
            if (exec instanceof Method m) {
                if ("new".equals(name)) {
                    return false;
                }
                if (!"*".equals(name) && !name.equals(m.getName())) {
                    return false;
                }
                boolean isStatic = Modifier.isStatic(m.getModifiers());
                if (kind == MemberKind.STATIC && !isStatic) {
                    return false;
                }
                if (kind == MemberKind.INSTANCE && isStatic) {
                    return false;
                }
                return !hasSignature || matchesParams(m.getParameterTypes());
            }
            return false;
        }

        boolean matchesMethodName(String methodName, boolean allowStatic, boolean allowInstance) {
            if ("new".equals(name)) {
                return false;
            }
            if (!"*".equals(name) && !name.equals(methodName)) {
                return false;
            }
            if (kind == MemberKind.STATIC && !allowStatic) {
                return false;
            }
            if (kind == MemberKind.INSTANCE && !allowInstance) {
                return false;
            }
            return true;
        }

        private boolean matchesParams(Class<?>[] paramTypes) {
            if (paramTypes.length != paramDescriptors.size()) {
                return false;
            }
            for (int i = 0; i < paramTypes.length; i++) {
                if (!jvmDescriptor(paramTypes[i]).equals(paramDescriptors.get(i))) {
                    return false;
                }
            }
            return true;
        }

        /** Converts this axiom to an {@link EffectChecker.JavaCallProfile} for {@code exec} (which may be null). */
        EffectChecker.JavaCallProfile toProfile(Executable exec) {
            boolean isCtor = exec instanceof Constructor<?> || isConstructor();
            boolean isStatic = isCtor || (exec != null && Modifier.isStatic(exec.getModifiers())) || kind == MemberKind.STATIC;
            int paramCount = exec != null ? exec.getParameterCount() : (hasSignature ? paramDescriptors.size() : 0);
            boolean[] mParams = new boolean[paramCount];
            boolean[] bParams = new boolean[paramCount];
            boolean[] uParams = new boolean[paramCount];
            if (effects.isIO()) {
                return new EffectChecker.JavaCallProfile(EffectSet.IO_DECLARED, false, false,
                        mParams, bParams, uParams, false, view, shallow, fresh);
            }
            if (hasSignature) {
                int len = Math.min(paramCount, mutParams.length);
                System.arraycopy(mutParams, 0, mParams, 0, len);
                System.arraycopy(borrowParams, 0, bParams, 0, len);
                System.arraycopy(uniqueParams, 0, uParams, 0, len);
            } else {
                if (!sharedReceiver) {
                    Arrays.fill(bParams, true);
                }
                if (isStatic && !isCtor && mutReceiver && paramCount > 0) {
                    mParams[0] = true;
                }
            }
            boolean effMutReceiver = !isStatic && mutReceiver;
            boolean effBorrowReceiver = !isStatic && !sharedReceiver && borrowReceiver;
            EffectSet effSet = effects;
            if (isStatic && (effects.mutThis() || !effects.borrowThis() != sharedReceiver)) {
                EnumSet<Effect> copy = EffectSet.copySet(effects.effects());
                boolean anyParamMut = false;
                for (boolean mp : mParams) {
                    anyParamMut |= mp;
                }
                if (!anyParamMut && !effects.isIO() && !effects.isVolatile() && effects.isHermetic()) {
                    // Preserve pure if no param is mutated.
                } else if (anyParamMut) {
                    copy.remove(Effect.PURE);
                }
                effSet = new EffectSet(copy, effects.declared(), false, !sharedReceiver, sharedReceiver,
                        effects.uniqueResult() || fresh, effects.explicitUniqueResult(),
                        effects.terminating(), effects.isIO(), effects.explicitVolatile(), effects.axiomatic());
            }
            return new EffectChecker.JavaCallProfile(effSet, effMutReceiver, effBorrowReceiver,
                    mParams, bParams, uParams, explicitNothrow, view, shallow, fresh);
        }

        /** Specificity rank within the same declaring class: higher wins. */
        int intraClassRank() {
            if (!isWildcard() && hasSignature) {
                return 3;
            }
            if (!isWildcard()) {
                return 2;
            }
            return 1;
        }
    }

    /** Class-level axiom flags ({@code exact}, {@code nullmarked}, {@code immutable}). */
    record ClassAxiom(
            Class<?> targetClass,
            boolean exact,
            boolean nullMarked,
            boolean immutable,
            List<MemberAxiom> members) {
    }

    /** Mutable builder / immutable table of axioms (for one package, or a program's local/exported axioms). */
    static final class AxiomTable {
        static final AxiomTable EMPTY = new AxiomTable(Map.of(), Map.of());

        private final Map<Class<?>, ClassAxiom> classAxioms;
        private final Map<Class<?>, List<MemberAxiom>> membersByOwner;

        AxiomTable(Map<Class<?>, ClassAxiom> classAxioms, Map<Class<?>, List<MemberAxiom>> membersByOwner) {
            this.classAxioms = classAxioms.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(classAxioms));
            if (membersByOwner.isEmpty()) {
                this.membersByOwner = Map.of();
            } else {
                Map<Class<?>, List<MemberAxiom>> copy = new LinkedHashMap<>();
                for (Map.Entry<Class<?>, List<MemberAxiom>> e : membersByOwner.entrySet()) {
                    copy.put(e.getKey(), List.copyOf(e.getValue()));
                }
                this.membersByOwner = Collections.unmodifiableMap(copy);
            }
        }

        boolean isEmpty() {
            return classAxioms.isEmpty() && membersByOwner.isEmpty();
        }

        Map<Class<?>, ClassAxiom> classAxioms() {
            return classAxioms;
        }

        Map<Class<?>, List<MemberAxiom>> membersByOwner() {
            return membersByOwner;
        }

        ClassAxiom directClassAxiom(Class<?> cls) {
            return classAxioms.get(cls);
        }

        List<MemberAxiom> directMembers(Class<?> cls) {
            return membersByOwner.getOrDefault(cls, List.of());
        }

        /** Merges {@code other} on top of {@code this} (entries in {@code other} take precedence). */
        AxiomTable mergeOver(AxiomTable other) {
            if (other == null || other.isEmpty()) {
                return this;
            }
            if (this.isEmpty()) {
                return other;
            }
            Map<Class<?>, ClassAxiom> mergedClasses = new LinkedHashMap<>(this.classAxioms);
            mergedClasses.putAll(other.classAxioms);
            Map<Class<?>, List<MemberAxiom>> mergedMembers = new LinkedHashMap<>();
            for (Map.Entry<Class<?>, List<MemberAxiom>> e : this.membersByOwner.entrySet()) {
                mergedMembers.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            for (Map.Entry<Class<?>, List<MemberAxiom>> e : other.membersByOwner.entrySet()) {
                List<MemberAxiom> list = mergedMembers.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                // Prepend newer entries so later/program axioms win on exact ties.
                list.addAll(0, e.getValue());
            }
            return new AxiomTable(mergedClasses, mergedMembers);
        }
    }

    /** Mutable accumulator for building an {@link AxiomTable}. */
    static final class Builder {
        private final Map<Class<?>, ClassAxiom> classAxioms = new LinkedHashMap<>();
        private final Map<Class<?>, List<MemberAxiom>> membersByOwner = new LinkedHashMap<>();

        void addClassAxiom(ClassAxiom cls) {
            classAxioms.put(cls.targetClass(), cls);
            for (MemberAxiom m : cls.members()) {
                addMemberAxiom(m);
            }
        }

        void addMemberAxiom(MemberAxiom member) {
            membersByOwner.computeIfAbsent(member.ownerClass(), k -> new ArrayList<>()).add(member);
        }

        void addAll(AxiomTable table) {
            if (table == null || table.isEmpty()) {
                return;
            }
            classAxioms.putAll(table.classAxioms());
            for (Map.Entry<Class<?>, List<MemberAxiom>> e : table.membersByOwner().entrySet()) {
                membersByOwner.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
            }
        }

        boolean hasClassAxiom(Class<?> cls) {
            return classAxioms.containsKey(cls);
        }

        boolean hasMemberConflict(MemberAxiom candidate) {
            List<MemberAxiom> existing = membersByOwner.get(candidate.ownerClass());
            if (existing == null) {
                return false;
            }
            for (MemberAxiom m : existing) {
                if (m.name().equals(candidate.name())
                        && m.hasSignature() == candidate.hasSignature()
                        && (!m.hasSignature() || m.paramDescriptors().equals(candidate.paramDescriptors()))
                        && kindsConflict(m.kind(), candidate.kind())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean kindsConflict(MemberKind a, MemberKind b) {
            return a == b || a == MemberKind.ANY || b == MemberKind.ANY;
        }

        AxiomTable build() {
            return new AxiomTable(classAxioms, membersByOwner);
        }
    }

    // ================================================================ Hierarchy Lookup

    /**
     * Collects {@code owner} and all its supertypes (superclasses and transitive interfaces)
     * in deterministic breadth-first specificity order: {@code owner}, superclasses up to
     * {@code Object}, then interfaces closest-first.
     */
    static List<Class<?>> supertypesOf(Class<?> owner) {
        if (owner == null) {
            return List.of();
        }
        List<Class<?>> result = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        // Walk class chain first, queueing interfaces along the way.
        Deque<Class<?>> ifaceQueue = new ArrayDeque<>();
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            if (seen.add(c)) {
                result.add(c);
            }
            for (Class<?> iface : c.getInterfaces()) {
                ifaceQueue.add(iface);
            }
        }
        while (!ifaceQueue.isEmpty()) {
            Class<?> iface = ifaceQueue.poll();
            if (seen.add(iface)) {
                result.add(iface);
                for (Class<?> parent : iface.getInterfaces()) {
                    ifaceQueue.add(parent);
                }
            }
        }
        // Interfaces don't have Object as superclass, but Object methods apply to all reference types.
        if (owner.isInterface() && seen.add(Object.class)) {
            result.add(Object.class);
        }
        return result;
    }

    /**
     * Finds the most specific matching {@link MemberAxiom} for a Java method across {@code owner}'s
     * type hierarchy, consulting {@code lookup} for each candidate class.
     */
    static MemberAxiom lookupMethod(Class<?> owner, String methodName, Method exactMethod,
                                    java.util.function.Function<Class<?>, List<MemberAxiom>> lookup) {
        if (owner == null || methodName == null) {
            return null;
        }
        List<Class<?>> supertypes = supertypesOf(owner);
        MemberAxiom best = null;
        for (Class<?> candidate : supertypes) {
            List<MemberAxiom> members = lookup.apply(candidate);
            if (members == null || members.isEmpty()) {
                continue;
            }
            for (MemberAxiom m : members) {
                if (m.exact() && candidate != owner) {
                    continue;
                }
                boolean matches = exactMethod != null
                        ? m.matches(exactMethod)
                        : m.matchesMethodName(methodName, true, true);
                if (!matches) {
                    continue;
                }
                if (best == null || isMoreSpecific(m, best)) {
                    best = m;
                }
            }
        }
        return best;
    }

    /**
     * Finds the most specific matching {@link MemberAxiom} for a Java constructor (constructors are
     * never inherited, so only {@code owner} itself is checked).
     */
    static MemberAxiom lookupConstructor(Class<?> owner, Constructor<?> exactCtor,
                                         java.util.function.Function<Class<?>, List<MemberAxiom>> lookup) {
        if (owner == null) {
            return null;
        }
        List<MemberAxiom> members = lookup.apply(owner);
        if (members == null || members.isEmpty()) {
            return null;
        }
        MemberAxiom best = null;
        for (MemberAxiom m : members) {
            boolean matches;
            if (exactCtor != null) {
                matches = m.matches(exactCtor);
            } else {
                matches = m.isConstructor() || (m.isWildcard() && m.kind() == MemberKind.STATIC);
            }
            if (!matches) {
                continue;
            }
            if (best == null || m.intraClassRank() > best.intraClassRank()) {
                best = m;
            }
        }
        return best;
    }

    /**
     * Finds the most specific non-exact {@link MemberAxiom} inherited from a set of Java supertypes
     * (used to verify Babble class overrides against Java interface / {@code Object} axiom contracts,
     * BIP019 §3.2).
     */
    static MemberAxiom lookupInheritedContract(Collection<Class<?>> javaSupertypes, String methodName, int arity,
                                               java.util.function.Function<Class<?>, List<MemberAxiom>> lookup) {
        if (javaSupertypes == null || javaSupertypes.isEmpty()) {
            return null;
        }
        Set<Class<?>> visited = new LinkedHashSet<>();
        for (Class<?> root : javaSupertypes) {
            visited.addAll(supertypesOf(root));
        }
        MemberAxiom best = null;
        for (Class<?> candidate : visited) {
            List<MemberAxiom> members = lookup.apply(candidate);
            if (members == null || members.isEmpty()) {
                continue;
            }
            for (MemberAxiom m : members) {
                if (m.exact() || m.isConstructor() || m.kind() == MemberKind.STATIC || m.isWildcard()) {
                    continue;
                }
                if (!m.name().equals(methodName)) {
                    continue;
                }
                if (m.hasSignature() && m.paramDescriptors().size() != arity) {
                    continue;
                }
                if (best == null || isMoreSpecific(m, best)) {
                    best = m;
                }
            }
        }
        return best;
    }

    /**
     * Whether {@code candidate} is strictly more specific than {@code current}:
     * 1. A named member beats a wildcard {@code *} (unless the wildcard is on a strictly more specific
     *    subtype and the named member is on a supertype — wait: a more specific subtype's named member
     *    beats a supertype's named member, and a supertype's named member beats nothing on a subtype
     *    unless the subtype also has a named member; specifically, subtype beats supertype, and within
     *    the same class named+sig > named > wildcard).
     */
    private static boolean isMoreSpecific(MemberAxiom candidate, MemberAxiom current) {
        Class<?> c1 = candidate.ownerClass();
        Class<?> c2 = current.ownerClass();
        if (c1 == c2) {
            return candidate.intraClassRank() > current.intraClassRank();
        }
        // Named member beats wildcard on a supertype, and subtype beats supertype.
        if (c2.isAssignableFrom(c1)) {
            // c1 is a strict subtype of c2.
            // A subtype's named axiom always overrides a supertype's axiom.
            // A subtype's wildcard also overrides a supertype's axiom (e.g. String's `def *: fn pure`
            // overrides Object's `hashCode`).
            return true;
        }
        if (c1.isAssignableFrom(c2)) {
            // c2 is a strict subtype of c1: keep current.
            return false;
        }
        // Unrelated interfaces/class: concrete class beats interface; otherwise higher intraClassRank wins.
        if (!c1.isInterface() && c2.isInterface()) {
            return true;
        }
        if (c1.isInterface() && !c2.isInterface()) {
            return false;
        }
        return candidate.intraClassRank() > current.intraClassRank();
    }

    static boolean lookupClassFlag(Class<?> owner,
                                   java.util.function.Function<Class<?>, ClassAxiom> lookup,
                                   java.util.function.Predicate<ClassAxiom> flag) {
        if (owner == null) {
            return false;
        }
        for (Class<?> candidate : supertypesOf(owner)) {
            ClassAxiom ca = lookup.apply(candidate);
            if (ca != null && (!ca.exact() || candidate == owner) && flag.test(ca)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ Parsing

    /** Diagnostic callback for axiom parsing errors. */
    @FunctionalInterface
    interface ErrorSink {
        void report(String message, ParserRuleContext at);
    }

    /** Optional class resolver (for user programs with imported Java packages). */
    @FunctionalInterface
    interface ClassResolver {
        Class<?> resolve(List<String> path);
    }

    /** Result of parsing one {@link BabbleParser.AxiomExpressionContext}. */
    record ParsedAxiom(ClassAxiom classAxiom, MemberAxiom memberAxiom, String babbleName, Type.Function babbleType) {
        boolean isJava() {
            return classAxiom != null || memberAxiom != null;
        }

        boolean isBabble() {
            return babbleName != null;
        }
    }

    /**
     * Parses a single {@link BabbleParser.AxiomExpressionContext} into either a Java class axiom,
     * a Java member axiom, or a Babble name axiom.
     */
    static ParsedAxiom parse(BabbleParser.AxiomExpressionContext ctx,
                             ClassResolver classResolver,
                             boolean curated,
                             ErrorSink errors) {
        List<String> targetPath = targetPathOf(ctx.target);
        BabbleParser.AxiomTypeContext typeCtx = ctx.axiomType();
        if (typeCtx.CLASS() != null) {
            Class<?> targetClass = resolveClass(targetPath, classResolver);
            if (targetClass == null) {
                errors.report("Unknown class in axiom : " + String.join(".", targetPath), ctx.target);
                return null;
            }
            Map<String, String> classTypeVars = buildClassTypeVars(ctx.typeParameters(), targetClass, errors);
            boolean exact = false;
            boolean nullMarked = false;
            boolean immutable = false;
            for (org.antlr.v4.runtime.Token w : typeCtx.classWords) {
                String text = w.getText();
                switch (text) {
                    case "exact" -> {
                        if (exact) errors.report("Duplicate axiom class modifier : exact", ctx);
                        exact = true;
                    }
                    case "nullmarked" -> {
                        if (nullMarked) errors.report("Duplicate axiom class modifier : nullmarked", ctx);
                        nullMarked = true;
                    }
                    case "immutable" -> {
                        if (immutable) errors.report("Duplicate axiom class modifier : immutable", ctx);
                        immutable = true;
                    }
                    default -> errors.report("Unknown axiom class modifier : " + text, ctx);
                }
            }
            List<MemberAxiom> members = new ArrayList<>();
            List<BabbleParser.AxiomMembersContext> sections = typeCtx.axiomMembers();
            if (!sections.isEmpty()) {
                parseMemberSection(sections.get(0), targetClass, MemberKind.INSTANCE, exact, classTypeVars, classResolver, curated, members, errors);
            }
            if (sections.size() > 1) {
                parseMemberSection(sections.get(1), targetClass, MemberKind.STATIC, exact, classTypeVars, classResolver, curated, members, errors);
            }
            return new ParsedAxiom(new ClassAxiom(targetClass, exact, nullMarked, immutable, List.copyOf(members)), null, null, null);
        }

        // Function axiom: either `axiom java.pkg.Class.member: fn ...` or `axiom babbleName: fn ...`
        if (targetPath.size() == 1) {
            // Babble name axiom (BIP019 §2.3 / P7)
            if (ctx.EXPORT() != null) {
                errors.report("Axiom about a Babble name cannot be exported : " + targetPath.get(0), ctx);
                return null;
            }
            if (typeCtx.fnTypeParameters() == null) {
                errors.report("Axiom about a Babble name requires a signature : " + targetPath.get(0), ctx);
                return null;
            }
            return new ParsedAxiom(null, null, targetPath.get(0), null);
        }

        List<String> ownerPath = targetPath.subList(0, targetPath.size() - 1);
        String memberName = targetPath.get(targetPath.size() - 1);
        Class<?> ownerClass = resolveClass(ownerPath, classResolver);
        if (ownerClass == null) {
            errors.report("Unknown class in axiom : " + String.join(".", ownerPath), ctx.target);
            return null;
        }
        Map<String, String> classTypeVars = buildClassTypeVars(ctx.typeParameters(), ownerClass, errors);
        MemberKind kind = "new".equals(memberName) ? MemberKind.STATIC : MemberKind.ANY;
        MemberAxiom member = parseFnAxiom(typeCtx, ownerClass, memberName, kind, false, classTypeVars, classResolver, curated, errors);
        if (member == null) {
            return null;
        }
        return new ParsedAxiom(null, member, null, null);
    }

    private static void parseMemberSection(BabbleParser.AxiomMembersContext section,
                                           Class<?> ownerClass,
                                           MemberKind kind,
                                           boolean exact,
                                           Map<String, String> classTypeVars,
                                           ClassResolver classResolver,
                                           boolean curated,
                                           List<MemberAxiom> out,
                                           ErrorSink errors) {
        for (BabbleParser.AxiomMemberContext mCtx : section.axiomMember()) {
            String memberName = mCtx.memberName() != null ? mCtx.memberName().getText() : "*";
            if (mCtx.axiomType().CLASS() != null) {
                errors.report("Axiom member must be a function type : " + memberName, mCtx);
                continue;
            }
            if ("new".equals(memberName) && kind == MemberKind.INSTANCE) {
                errors.report("Constructor axiom new must be declared in static (...) : " + ownerClass.getName(), mCtx);
                continue;
            }
            MemberAxiom member = parseFnAxiom(mCtx.axiomType(), ownerClass, memberName, kind, exact, classTypeVars, classResolver, curated, errors);
            if (member != null) {
                out.add(member);
            }
        }
    }

    private static MemberAxiom parseFnAxiom(BabbleParser.AxiomTypeContext typeCtx,
                                            Class<?> ownerClass,
                                            String memberName,
                                            MemberKind kind,
                                            boolean exact,
                                            Map<String, String> classTypeVars,
                                            ClassResolver classResolver,
                                            boolean curated,
                                            ErrorSink errors) {
        BabbleParser.AxiomEffectPrefixContext prefix = typeCtx.axiomEffectPrefix();
        BabbleParser.FnTypeParametersContext fnParams = typeCtx.fnTypeParameters();
        if (prefix == null && fnParams == null) {
            errors.report("Empty axiom : " + ownerClass.getName() + "." + memberName, typeCtx);
            return null;
        }

        boolean io = false;
        boolean volatileEff = false;
        boolean explicitPure = false;
        boolean mutReceiver = false;
        boolean sharedReceiver = false;
        boolean nothrow = false;
        boolean uniqueReturn = typeCtx.uniqueReturn != null;
        boolean view = false;
        boolean shallow = false;
        boolean fresh = false;
        boolean foldable = false;
        EnumSet<Effect> algebraic = EnumSet.noneOf(Effect.class);

        if (prefix != null) {
            for (BabbleParser.AxiomEffectWordContext w : prefix.axiomEffectWord()) {
                String text = w.getText();
                switch (text) {
                    case "io" -> io = true;
                    case "volatile" -> volatileEff = true;
                    case "pure" -> explicitPure = true;
                    case "mut" -> mutReceiver = true;
                    case "shared" -> sharedReceiver = true;
                    case "nothrow" -> nothrow = true;
                    case "unique" -> uniqueReturn = true;
                    case "idempotent" -> algebraic.add(Effect.IDEMPOTENT);
                    case "commutative" -> algebraic.add(Effect.COMMUTATIVE);
                    case "associative" -> algebraic.add(Effect.ASSOCIATIVE);
                    case "monotone" -> algebraic.add(Effect.MONOTONE);
                    case "injective" -> algebraic.add(Effect.INJECTIVE);
                    case "view" -> view = true;
                    case "shallow" -> shallow = true;
                    case "fresh" -> {
                        fresh = true;
                        uniqueReturn = true;
                    }
                    case "foldable" -> foldable = true;
                    default -> errors.report("Unknown effect modifier : " + text, w);
                }
            }
        }

        if (io && (explicitPure || volatileEff || mutReceiver || sharedReceiver || nothrow || !algebraic.isEmpty()
                || view || shallow || fresh || foldable)) {
            errors.report("io cannot be combined with other effect modifiers in axiom : " + ownerClass.getName() + "." + memberName, typeCtx);
        }
        if (explicitPure && (mutReceiver || volatileEff)) {
            errors.report("pure cannot be combined with mut or volatile in axiom : " + ownerClass.getName() + "." + memberName, typeCtx);
        }

        Map<String, String> allTypeVars = new LinkedHashMap<>(classTypeVars);
        if (typeCtx.typeParameters() != null) {
            for (TerminalNode id : typeCtx.typeParameters().ID()) {
                allTypeVars.put(id.getText(), "Ljava/lang/Object;");
            }
        }

        boolean hasSignature = fnParams != null;
        List<String> paramDescriptors = List.of();
        JavaNullability.Nullness[] paramNullness = new JavaNullability.Nullness[0];
        boolean[] mutParams = new boolean[0];
        boolean[] borrowParams = new boolean[0];
        boolean[] uniqueParams = new boolean[0];
        boolean[] sharedParams = new boolean[0];
        String returnDescriptor = null;
        JavaNullability.Nullness returnNullness = null;
        boolean anyParamMut = false;

        if (hasSignature) {
            List<BabbleParser.FnTypeParamContext> params = fnParams.fnTypeParam();
            int n = params.size();
            List<String> descs = new ArrayList<>(n);
            paramNullness = new JavaNullability.Nullness[n];
            mutParams = new boolean[n];
            borrowParams = new boolean[n];
            uniqueParams = new boolean[n];
            sharedParams = new boolean[n];
            for (int i = 0; i < n; i++) {
                BabbleParser.FnTypeParamContext p = params.get(i);
                boolean pMut = false;
                boolean pShared = false;
                boolean pUnique = false;
                for (BabbleParser.ParamModifierContext pm : p.paramModifier()) {
                    if (pm.MUT() != null) pMut = true;
                    else if (pm.SHARED() != null) pShared = true;
                    else if (pm.UNIQUE() != null) pUnique = true;
                }
                if (pShared && pUnique) {
                    errors.report("Parameter cannot be both shared and unique", p);
                }
                mutParams[i] = pMut;
                sharedParams[i] = pShared;
                uniqueParams[i] = pUnique;
                borrowParams[i] = !pShared && !pUnique;
                anyParamMut |= pMut;
                TypeErasure te = eraseType(p.type(), allTypeVars, classResolver, errors);
                descs.add(te.descriptor());
                paramNullness[i] = te.nullness();
            }
            paramDescriptors = List.copyOf(descs);
            if (typeCtx.type() != null) {
                TypeErasure ret = eraseType(typeCtx.type(), allTypeVars, classResolver, errors);
                returnDescriptor = ret.descriptor();
                returnNullness = ret.nullness();
            }
        }

        EffectSet effectSet;
        if (io) {
            effectSet = EffectSet.IO_DECLARED;
        } else {
            EnumSet<Effect> effs = EnumSet.copyOf(algebraic);
            effs.add(Effect.HERMETIC);
            if (!volatileEff) {
                effs.add(Effect.DETERMINISTIC);
            }
            if (!mutReceiver && !anyParamMut) {
                effs.add(Effect.PURE);
            }
            if (nothrow) {
                effs.add(Effect.NOTHROW);
            }
            boolean isCtor = "new".equals(memberName);
            boolean borrowThis = !sharedReceiver;
            boolean terminating = curated && !isCtor;
            effectSet = new EffectSet(effs, effs, mutReceiver && !isCtor, borrowThis, sharedReceiver,
                    uniqueReturn, uniqueReturn, terminating, false, volatileEff, true);
        }

        boolean[] readParams = new boolean[paramDescriptors.size()];
        Arrays.fill(readParams, true);
        return new MemberAxiom(
                ownerClass, memberName, kind, exact, hasSignature,
                paramDescriptors, returnDescriptor, paramNullness, returnNullness,
                effectSet, mutReceiver, !sharedReceiver, sharedReceiver,
                mutParams, borrowParams, uniqueParams, sharedParams,
                view, shallow, fresh, foldable, nothrow, true, readParams, curated);
    }

    private static Map<String, String> buildClassTypeVars(BabbleParser.TypeParametersContext tpCtx,
                                                          Class<?> targetClass,
                                                          ErrorSink errors) {
        if (tpCtx == null) {
            return Map.of();
        }
        Map<String, String> vars = new LinkedHashMap<>();
        TypeVariable<?>[] reflected = targetClass.getTypeParameters();
        List<TerminalNode> ids = tpCtx.ID();
        for (int i = 0; i < ids.size(); i++) {
            String name = ids.get(i).getText();
            String boundDesc = "Ljava/lang/Object;";
            if (i < reflected.length) {
                java.lang.reflect.Type[] bounds = reflected[i].getBounds();
                if (bounds.length > 0 && bounds[0] instanceof Class<?> bCls) {
                    boundDesc = jvmDescriptor(bCls);
                } else if (bounds.length > 0 && bounds[0] instanceof java.lang.reflect.ParameterizedType pt
                        && pt.getRawType() instanceof Class<?> raw) {
                    boundDesc = jvmDescriptor(raw);
                }
            }
            if (vars.put(name, boundDesc) != null) {
                errors.report("Duplicate type parameter in axiom : " + name, tpCtx);
            }
        }
        return vars;
    }

    static List<String> targetPathOf(BabbleParser.AxiomTargetContext target) {
        List<String> path = new ArrayList<>();
        if (target.ID() != null) {
            path.add(target.ID().getText());
        } else if (target.coreScalarKeyword() != null) {
            path.add(target.coreScalarKeyword().getText());
        }
        for (BabbleParser.MemberNameContext m : target.memberName()) {
            path.add(m.getText());
        }
        return path;
    }

    private static Class<?> resolveClass(List<String> path, ClassResolver resolver) {
        if (path.isEmpty()) {
            return null;
        }
        if (resolver != null) {
            Class<?> resolved = resolver.resolve(path);
            if (resolved != null) {
                return resolved;
            }
        }
        return JavaTypeBridge.loadClass(String.join(".", path));
    }

    // ================================================================ Type Erasure & Nullness

    record TypeErasure(String descriptor, JavaNullability.Nullness nullness, boolean isTypeVar) {
    }

    static TypeErasure eraseType(BabbleParser.TypeContext typeCtx,
                                 Map<String, String> typeVars,
                                 ClassResolver classResolver,
                                 ErrorSink errors) {
        // Group operands split by '|' (union) vs '&' (intersection).
        List<List<BabbleParser.NullableTypeContext>> unionMembers = new ArrayList<>();
        List<BabbleParser.NullableTypeContext> currentGroup = new ArrayList<>();
        for (int i = 0; i < typeCtx.getChildCount(); i++) {
            ParseTree child = typeCtx.getChild(i);
            if (child instanceof BabbleParser.NullableTypeContext nt) {
                currentGroup.add(nt);
            } else if (child instanceof TerminalNode tn && tn.getSymbol().getType() == BabbleLexer.PIPE) {
                unionMembers.add(currentGroup);
                currentGroup = new ArrayList<>();
            }
        }
        unionMembers.add(currentGroup);

        boolean hasNull = false;
        List<TypeErasure> nonNullMembers = new ArrayList<>();
        for (List<BabbleParser.NullableTypeContext> group : unionMembers) {
            if (group.size() == 1) {
                BabbleParser.NullableTypeContext single = group.get(0);
                if (single instanceof BabbleParser.NonParenTypeContext npt
                        && npt.nonParenNullableType() instanceof BabbleParser.NullTypeContext) {
                    hasNull = true;
                    continue;
                }
                nonNullMembers.add(eraseNullable(single, typeVars, classResolver, errors));
            } else {
                // Intersection A & B erases to its first operand (BIP022).
                TypeErasure first = eraseNullable(group.get(0), typeVars, classResolver, errors);
                for (int i = 1; i < group.size(); i++) {
                    eraseNullable(group.get(i), typeVars, classResolver, errors);
                }
                nonNullMembers.add(first);
            }
        }
        if (nonNullMembers.isEmpty()) {
            return new TypeErasure("Ljava/lang/Object;", JavaNullability.Nullness.NULLABLE, false);
        }
        if (nonNullMembers.size() == 1) {
            TypeErasure only = nonNullMembers.get(0);
            if (hasNull) {
                return new TypeErasure(boxDescriptor(only.descriptor()), JavaNullability.Nullness.NULLABLE, false);
            }
            return only;
        }
        // Multi-member union erases to Object.
        boolean anyNullable = hasNull || nonNullMembers.stream().anyMatch(m -> m.nullness() == JavaNullability.Nullness.NULLABLE);
        return new TypeErasure("Ljava/lang/Object;",
                anyNullable ? JavaNullability.Nullness.NULLABLE : JavaNullability.Nullness.NON_NULL, false);
    }

    private static TypeErasure eraseNullable(BabbleParser.NullableTypeContext ctx,
                                             Map<String, String> typeVars,
                                             ClassResolver classResolver,
                                             ErrorSink errors) {
        if (ctx instanceof BabbleParser.ParenTypeContext paren) {
            TypeErasure inner = eraseType(paren.type(), typeVars, classResolver, errors);
            if (paren.question != null) {
                return new TypeErasure(boxDescriptor(inner.descriptor()), JavaNullability.Nullness.NULLABLE, false);
            }
            return inner;
        }
        BabbleParser.NonParenNullableTypeContext primary = ((BabbleParser.NonParenTypeContext) ctx).nonParenNullableType();
        if (primary instanceof BabbleParser.NullTypeContext) {
            return new TypeErasure("Ljava/lang/Object;", JavaNullability.Nullness.NULLABLE, false);
        }
        if (primary instanceof BabbleParser.FunctionTypeContext) {
            return new TypeErasure("Lorg/babblelang/runtime/Callable;", JavaNullability.Nullness.NON_NULL, false);
        }
        BabbleParser.SimpleTypeContext simple = (BabbleParser.SimpleTypeContext) primary;
        boolean nullable = simple.question != null;
        List<String> path = ScopeAnalysis.simpleTypePath(simple);
        if (path.size() == 1) {
            String single = path.get(0);
            if (typeVars.containsKey(single)) {
                String desc = typeVars.get(single);
                JavaNullability.Nullness nullness = nullable
                        ? JavaNullability.Nullness.NULLABLE
                        : JavaNullability.Nullness.PARAMETRIC;
                return new TypeErasure(desc, nullness, !nullable);
            }
            if ("array".equals(single)) {
                TypeErasure elem = eraseArrayElement(simple, typeVars, classResolver, errors);
                String arrDesc = "[" + elem.descriptor();
                JavaNullability.Nullness nullness = nullable
                        ? JavaNullability.Nullness.NULLABLE
                        : JavaNullability.Nullness.NON_NULL;
                return new TypeErasure(arrDesc, nullness, false);
            }
            String builtinDesc = switch (single) {
                case "byte" -> nullable ? "Ljava/lang/Byte;" : "B";
                case "short" -> nullable ? "Ljava/lang/Short;" : "S";
                case "int" -> nullable ? "Ljava/lang/Integer;" : "I";
                case "long" -> nullable ? "Ljava/lang/Long;" : "J";
                case "float" -> nullable ? "Ljava/lang/Float;" : "F";
                case "double" -> nullable ? "Ljava/lang/Double;" : "D";
                case "bool" -> nullable ? "Ljava/lang/Boolean;" : "Z";
                case "char" -> nullable ? "Ljava/lang/Character;" : "C";
                case "string" -> "Ljava/lang/String;";
                case "any" -> "Ljava/lang/Object;";
                case "void" -> "V";
                case "list" -> "Ljava/util/List;";
                case "set" -> "Ljava/util/Set;";
                case "sortedset" -> "Ljava/util/SortedSet;";
                case "map" -> "Ljava/util/Map;";
                case "sortedmap" -> "Ljava/util/SortedMap;";
                case "entry" -> "Ljava/util/Map$Entry;";
                default -> null;
            };
            if (builtinDesc != null) {
                // Validate type arguments if any.
                validateNestedTypeArgs(simple, typeVars, classResolver, errors);
                return new TypeErasure(builtinDesc,
                        nullable ? JavaNullability.Nullness.NULLABLE : JavaNullability.Nullness.NON_NULL, false);
            }
        }
        Class<?> cls = resolveClass(path, classResolver);
        if (cls == null) {
            errors.report("Unknown type : " + String.join(".", path), simple);
            return new TypeErasure("Ljava/lang/Object;", JavaNullability.Nullness.UNSPECIFIED, false);
        }
        validateNestedTypeArgs(simple, typeVars, classResolver, errors);
        return new TypeErasure(jvmDescriptor(cls),
                nullable ? JavaNullability.Nullness.NULLABLE : JavaNullability.Nullness.NON_NULL, false);
    }

    private static TypeErasure eraseArrayElement(BabbleParser.SimpleTypeContext simple,
                                                 Map<String, String> typeVars,
                                                 ClassResolver classResolver,
                                                 ErrorSink errors) {
        if (simple.OF() == null) {
            errors.report("Missing type arguments : array", simple);
            return new TypeErasure("Ljava/lang/Object;", JavaNullability.Nullness.UNSPECIFIED, false);
        }
        if (simple.nonParenNullableType() != null) {
            // Wrap in a synthetic evaluation of the single non-paren type.
            return eraseNonParen(simple.nonParenNullableType(), typeVars, classResolver, errors);
        }
        if (simple.type().size() != 1) {
            errors.report("Wrong number of type arguments : expected 1, got " + simple.type().size(), simple);
            return new TypeErasure("Ljava/lang/Object;", JavaNullability.Nullness.UNSPECIFIED, false);
        }
        return eraseType(simple.type(0), typeVars, classResolver, errors);
    }

    private static TypeErasure eraseNonParen(BabbleParser.NonParenNullableTypeContext primary,
                                             Map<String, String> typeVars,
                                             ClassResolver classResolver,
                                             ErrorSink errors) {
        if (primary instanceof BabbleParser.NullTypeContext) {
            return new TypeErasure("Ljava/lang/Object;", JavaNullability.Nullness.NULLABLE, false);
        }
        if (primary instanceof BabbleParser.FunctionTypeContext) {
            return new TypeErasure("Lorg/babblelang/runtime/Callable;", JavaNullability.Nullness.NON_NULL, false);
        }
        BabbleParser.SimpleTypeContext simple = (BabbleParser.SimpleTypeContext) primary;
        boolean nullable = simple.question != null;
        List<String> path = ScopeAnalysis.simpleTypePath(simple);
        if (path.size() == 1) {
            String single = path.get(0);
            if (typeVars.containsKey(single)) {
                return new TypeErasure(typeVars.get(single),
                        nullable ? JavaNullability.Nullness.NULLABLE : JavaNullability.Nullness.PARAMETRIC, !nullable);
            }
            if ("array".equals(single)) {
                TypeErasure elem = eraseArrayElement(simple, typeVars, classResolver, errors);
                return new TypeErasure("[" + elem.descriptor(),
                        nullable ? JavaNullability.Nullness.NULLABLE : JavaNullability.Nullness.NON_NULL, false);
            }
            String builtinDesc = switch (single) {
                case "byte" -> nullable ? "Ljava/lang/Byte;" : "B";
                case "short" -> nullable ? "Ljava/lang/Short;" : "S";
                case "int" -> nullable ? "Ljava/lang/Integer;" : "I";
                case "long" -> nullable ? "Ljava/lang/Long;" : "J";
                case "float" -> nullable ? "Ljava/lang/Float;" : "F";
                case "double" -> nullable ? "Ljava/lang/Double;" : "D";
                case "bool" -> nullable ? "Ljava/lang/Boolean;" : "Z";
                case "char" -> nullable ? "Ljava/lang/Character;" : "C";
                case "string" -> "Ljava/lang/String;";
                case "any" -> "Ljava/lang/Object;";
                case "void" -> "V";
                case "list" -> "Ljava/util/List;";
                case "set" -> "Ljava/util/Set;";
                case "sortedset" -> "Ljava/util/SortedSet;";
                case "map" -> "Ljava/util/Map;";
                case "sortedmap" -> "Ljava/util/SortedMap;";
                case "entry" -> "Ljava/util/Map$Entry;";
                default -> null;
            };
            if (builtinDesc != null) {
                validateNestedTypeArgs(simple, typeVars, classResolver, errors);
                return new TypeErasure(builtinDesc,
                        nullable ? JavaNullability.Nullness.NULLABLE : JavaNullability.Nullness.NON_NULL, false);
            }
        }
        Class<?> cls = resolveClass(path, classResolver);
        if (cls == null) {
            errors.report("Unknown type : " + String.join(".", path), simple);
            return new TypeErasure("Ljava/lang/Object;", JavaNullability.Nullness.UNSPECIFIED, false);
        }
        validateNestedTypeArgs(simple, typeVars, classResolver, errors);
        return new TypeErasure(jvmDescriptor(cls),
                nullable ? JavaNullability.Nullness.NULLABLE : JavaNullability.Nullness.NON_NULL, false);
    }

    private static void validateNestedTypeArgs(BabbleParser.SimpleTypeContext simple,
                                               Map<String, String> typeVars,
                                               ClassResolver classResolver,
                                               ErrorSink errors) {
        if (simple.OF() == null) {
            return;
        }
        if (simple.nonParenNullableType() != null) {
            eraseNonParen(simple.nonParenNullableType(), typeVars, classResolver, errors);
        } else {
            for (BabbleParser.TypeContext t : simple.type()) {
                eraseType(t, typeVars, classResolver, errors);
            }
        }
    }

    static String jvmDescriptor(Class<?> cls) {
        return org.objectweb.asm.Type.getDescriptor(cls);
    }

    private static String boxDescriptor(String desc) {
        return switch (desc) {
            case "B" -> "Ljava/lang/Byte;";
            case "S" -> "Ljava/lang/Short;";
            case "I" -> "Ljava/lang/Integer;";
            case "J" -> "Ljava/lang/Long;";
            case "F" -> "Ljava/lang/Float;";
            case "D" -> "Ljava/lang/Double;";
            case "Z" -> "Ljava/lang/Boolean;";
            case "C" -> "Ljava/lang/Character;";
            default -> desc;
        };
    }

    // ================================================================ Canonical Order Checking (§3.4)

    /**
     * Checks canonical order of a parsed {@code curated.ba} file (§3.4) and returns a list of
     * violation messages (empty if canonical).
     */
    static List<String> checkCanonicalOrder(BabbleParser.FileContext file, String expectedPackage) {
        List<String> violations = new ArrayList<>();
        String prevClassBinary = null;
        boolean prevWasClassAxiom = false;
        String prevMemberName = null;
        List<String> prevMemberParams = null;

        for (BabbleParser.ExpressionContext expr : file.expression()) {
            if (!(expr instanceof BabbleParser.AxiomExpressionContext ax)) {
                violations.add("Non-axiom expression in curated.ba at line " + expr.getStart().getLine());
                continue;
            }
            List<String> path = targetPathOf(ax.target);
            boolean isClass = ax.axiomType().CLASS() != null;
            List<String> clsPath = isClass ? path : path.subList(0, path.size() - 1);
            Class<?> cls = JavaTypeBridge.loadClass(String.join(".", clsPath));
            if (cls == null) {
                violations.add("Unknown class " + String.join(".", clsPath) + " at line " + ax.getStart().getLine());
                continue;
            }
            if (expectedPackage != null && !expectedPackage.equals(cls.getPackageName())) {
                violations.add("Class " + cls.getName() + " does not belong to package " + expectedPackage);
            }
            String binary = cls.getName();
            String memberName = isClass ? null : path.get(path.size() - 1);
            List<String> memberParams = List.of();
            if (!isClass) {
                List<String> errs = new ArrayList<>();
                ParsedAxiom parsed = parse(ax, null, true, (m, at) -> errs.add(m));
                if (parsed != null && parsed.memberAxiom() != null) {
                    memberParams = parsed.memberAxiom().paramDescriptors();
                }
            }

            if (prevClassBinary != null) {
                int cmp = binary.compareTo(prevClassBinary);
                if (cmp < 0) {
                    violations.add("Axiom for " + binary + " must appear before " + prevClassBinary);
                } else if (cmp == 0) {
                    if (isClass && !prevWasClassAxiom) {
                        violations.add("Class axiom for " + binary + " must appear before its member axioms");
                    } else if (!isClass && !prevWasClassAxiom) {
                        int mCmp = compareMemberOrder(memberName, memberParams, prevMemberName, prevMemberParams);
                        if (mCmp < 0) {
                            violations.add("Member axiom " + binary + "." + memberName + " is out of order after " + prevMemberName);
                        }
                    }
                }
            }
            prevClassBinary = binary;
            prevWasClassAxiom = isClass;
            prevMemberName = memberName;
            prevMemberParams = memberParams;

            if (isClass) {
                checkClassWordOrder(ax.axiomType(), binary, violations);
                List<BabbleParser.AxiomMembersContext> sections = ax.axiomType().axiomMembers();
                Map<String, String> classTypeVars = buildClassTypeVars(ax.typeParameters(), cls, (m, at) -> {});
                for (BabbleParser.AxiomMembersContext sec : sections) {
                    checkMemberSectionOrder(sec, cls, classTypeVars, violations);
                }
            } else {
                checkEffectWordOrder(ax.axiomType(), binary + "." + memberName, violations);
            }
        }
        return violations;
    }

    private static void checkClassWordOrder(BabbleParser.AxiomTypeContext typeCtx, String binary, List<String> violations) {
        int prevRank = -1;
        for (org.antlr.v4.runtime.Token w : typeCtx.classWords) {
            int rank = CLASS_WORD_ORDER.indexOf(w.getText());
            if (rank < 0) {
                violations.add("Unknown class word '" + w.getText() + "' on " + binary);
            } else if (rank <= prevRank) {
                violations.add("Class word '" + w.getText() + "' out of canonical order on " + binary);
            } else {
                prevRank = rank;
            }
        }
    }

    private static void checkEffectWordOrder(BabbleParser.AxiomTypeContext typeCtx, String label, List<String> violations) {
        BabbleParser.AxiomEffectPrefixContext prefix = typeCtx.axiomEffectPrefix();
        if (prefix == null) {
            return;
        }
        int prevRank = -1;
        for (BabbleParser.AxiomEffectWordContext w : prefix.axiomEffectWord()) {
            int rank = EFFECT_WORD_ORDER.indexOf(w.getText());
            if (rank < 0) {
                violations.add("Unknown effect word '" + w.getText() + "' on " + label);
            } else if (rank <= prevRank) {
                violations.add("Effect word '" + w.getText() + "' out of canonical order on " + label);
            } else {
                prevRank = rank;
            }
        }
    }

    private static void checkMemberSectionOrder(BabbleParser.AxiomMembersContext section,
                                                Class<?> cls,
                                                Map<String, String> classTypeVars,
                                                List<String> violations) {
        String prevName = null;
        List<String> prevParams = null;
        boolean prevHasSig = false;
        for (BabbleParser.AxiomMemberContext mCtx : section.axiomMember()) {
            String name = mCtx.memberName() != null ? mCtx.memberName().getText() : "*";
            checkEffectWordOrder(mCtx.axiomType(), cls.getName() + "." + name, violations);
            MemberAxiom parsed = parseFnAxiom(mCtx.axiomType(), cls, name, MemberKind.ANY, false, classTypeVars, null, true, (m, at) -> {});
            boolean hasSig = parsed != null && parsed.hasSignature();
            List<String> params = parsed != null ? parsed.paramDescriptors() : List.of();
            if (prevName != null) {
                int cmp = compareMemberOrder(name, params, prevName, prevParams);
                if (cmp < 0 || (cmp == 0 && !hasSig && prevHasSig)) {
                    violations.add("Member " + cls.getName() + "." + name + params + " out of canonical order after " + prevName + prevParams);
                }
            }
            prevName = name;
            prevParams = params;
            prevHasSig = hasSig;
        }
    }

    static int compareMemberOrder(String nameA, List<String> paramsA, String nameB, List<String> paramsB) {
        int rA = memberNameRank(nameA);
        int rB = memberNameRank(nameB);
        if (rA != rB) {
            return Integer.compare(rA, rB);
        }
        int nameCmp = nameA.compareTo(nameB);
        if (nameCmp != 0) {
            return nameCmp;
        }
        int min = Math.min(paramsA.size(), paramsB.size());
        for (int i = 0; i < min; i++) {
            int c = paramsA.get(i).compareTo(paramsB.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(paramsA.size(), paramsB.size());
    }

    private static int memberNameRank(String name) {
        if ("*".equals(name)) return 0;
        if ("new".equals(name)) return 1;
        return 2;
    }

    /** Comparator for sorting {@link MemberAxiom}s in canonical §3.4 order. */
    static final Comparator<MemberAxiom> CANONICAL_MEMBER_COMPARATOR = (a, b) -> {
        int c = compareMemberOrder(a.name(), a.paramDescriptors(), b.name(), b.paramDescriptors());
        if (c != 0) return c;
        return Boolean.compare(a.hasSignature(), b.hasSignature());
    };
}
