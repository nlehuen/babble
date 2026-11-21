package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * A static type, as the type checker computes it (see docs/design/bip008_static-typing.md).
 */
sealed interface Type {
    /** The built-in types, and the special ones. */
    record Simple(String name, Class<?> javaClass) implements Type {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Internal sentinel for an expression or declaration whose type failed to resolve (never a language type). */
    Simple ERROR = new Simple("<error>", null);
    /** The sound non-null top type : assignable from every non-null type, usable only once narrowed or cast. */
    Simple ANY = new Simple("any", Object.class);
    /** The type of an expression that never yields a value : {@code return}. */
    Simple NOTHING = new Simple("nothing", null);
    Simple NULL = new Simple("null", null);
    Simple BYTE = new Simple("byte", Byte.class);
    Simple SHORT = new Simple("short", Short.class);
    Simple INT = new Simple("int", Integer.class);
    Simple LONG = new Simple("long", Long.class);
    Simple FLOAT = new Simple("float", Float.class);
    Simple DOUBLE = new Simple("double", Double.class);
    Simple BOOL = new Simple("bool", Boolean.class);
    Simple CHAR = new Simple("char", Character.class);
    Simple STRING = new Simple("string", String.class);

    /** Position in the strict widening chain {@code byte < short < int < long < float < double}, or -1. */
    static int numericRank(Type type) {
        if (type == BYTE) return 0;
        if (type == SHORT) return 1;
        if (type == INT) return 2;
        if (type == LONG) return 3;
        if (type == FLOAT) return 4;
        if (type == DOUBLE) return 5;
        return -1;
    }

    static Simple numericOfRank(int rank) {
        return switch (rank) {
            case 0 -> BYTE;
            case 1 -> SHORT;
            case 2 -> INT;
            case 3 -> LONG;
            case 4 -> FLOAT;
            case 5 -> DOUBLE;
            default -> throw new IllegalArgumentException("Invalid numeric rank: " + rank);
        };
    }

    static boolean isNumericScalar(Type type) {
        return numericRank(type) >= 0;
    }

    static boolean isPrimitiveScalar(Type type) {
        return isNumericScalar(type) || type == BOOL || type == CHAR;
    }

    static boolean isCoreScalar(Type type) {
        return isNumericScalar(type) || type == BOOL || type == CHAR || type == STRING;
    }

    /** A union of distinct non-union, non-nothing, non-error types (with {@code null}, if present, last). */
    record Union(List<Type> members) implements Type {
        @Override
        public String toString() {
            if (members.size() == 2 && members.get(1) == NULL) {
                Type base = members.get(0);
                return (base instanceof Function || base instanceof Intersection || base instanceof Array ? "(" + base + ")" : base.toString()) + "?";
            }
            return members.stream()
                    .map(m -> m instanceof Function ? "(" + m + ")" : m.toString())
                    .collect(Collectors.joining(" | "));
        }
    }

    /** The universal nullable top type {@code any?}. */
    Type NULLABLE_ANY = new Union(List.of(ANY, NULL));

    /**
     * An intersection of interface types (BIP022) : the values that are all of its members.
     * Built by {@link #intersection}, so it has at least two distinct members, none of them an
     * intersection, {@code any} or a type another member implies ; a member may be a union of
     * interfaces, which relations distribute (BIP022-D2).
     */
    record Intersection(List<Type> members) implements Type {
        @Override
        public String toString() {
            return members.stream()
                    .map(m -> m instanceof Union || m instanceof Function ? "(" + m + ")" : m.toString())
                    .collect(Collectors.joining(" & "));
        }
    }

    static Type intersection(Type... types) {
        return intersection(Arrays.asList(types));
    }

    /**
     * The normal form of an intersection (BIP022-D2) : nested intersections flattened,
     * {@code any} and duplicates dropped, and a member another member implies dropped
     * ({@code Shape & Base} is {@code Shape} when {@code Shape extends Base}) ; one member left
     * is that member.
     */
    static Type intersection(Collection<? extends Type> types) {
        List<Type> flat = new ArrayList<>();
        for (Type t : types) {
            if (t == ERROR || t == NOTHING) {
                return t;
            }
            if (t instanceof Intersection i) {
                flat.addAll(i.members());
            } else if (t != ANY) {
                flat.add(t);
            }
        }
        List<Type> kept = new ArrayList<>(flat.size());
        for (int i = 0; i < flat.size(); i++) {
            Type candidate = flat.get(i);
            boolean redundant = false;
            for (int j = 0; j < flat.size() && !redundant; j++) {
                if (i == j) {
                    continue;
                }
                Type other = flat.get(j);
                redundant = other.equals(candidate) ? j < i : impliesNominally(other, candidate);
            }
            if (!redundant) {
                kept.add(candidate);
            }
        }
        if (kept.isEmpty()) {
            return ANY;
        }
        return kept.size() == 1 ? kept.get(0) : new Intersection(List.copyOf(kept));
    }

    /** Whether every value of {@code sub} is a {@code sup}, by declared interfaces alone (no type arguments on {@code sup}). */
    private static boolean impliesNominally(Type sub, Type sup) {
        if (sub instanceof Instance s && sup instanceof Instance t && t.typeArguments().isEmpty()) {
            return s.node() != t.node() && TypeAlgebra.isSubtype(s.node(), t.node());
        }
        if (sub instanceof JavaInstance s && sup instanceof JavaInstance t && t.typeArguments().isEmpty()) {
            return s.type() != t.type() && t.type().isAssignableFrom(s.type());
        }
        return false;
    }

    /** A JVM array {@code array of T} (BIP020-D1). */
    record Array(Type element) implements Type {
        @Override
        public String toString() {
            return "array of " + formatElement(element);
        }

        private static String formatElement(Type t) {
            return isSimpleElement(t) ? t.toString() : "(" + t + ")";
        }

        private static boolean isSimpleElement(Type t) {
            return switch (t) {
                case Simple ignored -> true;
                case TypeVar ignored -> true;
                case Instance i -> i.typeArguments().isEmpty();
                case JavaInstance j -> j.typeArguments().isEmpty();
                case CoreCollection c -> c.typeArguments().isEmpty();
                case Union u -> u.members().size() == 2 && u.members().get(1) == NULL && isSimpleElement(u.members().get(0));
                default -> false;
            };
        }
    }

    /** Opaque identity key for type variables without retaining AST nodes. */
    static final class SymbolId {
        private final String label;

        SymbolId(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** A Babble generic type parameter declared on a class, interface or function def. */
    record TypeVar(SymbolId owner, String name, int index) implements Type {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A deeply immutable frozen view of a nominal/reference type (BIP007-P2, BIP007-D7). */
    record Frozen(Type of) implements Type {
        @Override
        public String toString() {
            return "frozen " + (of instanceof Union || of instanceof Function || of instanceof Intersection ? "(" + of + ")" : of);
        }
    }

    /**
     * Wraps {@code type} in {@link Frozen} per BIP007-D7 : simplifies only on the 9 core scalars
     * and {@code null}, distributes over {@link Union} ({@code frozen T?} becomes
     * {@code (frozen T)?}), and never unwraps nominal/reference types.
     */
    static Type frozen(Type type) {
        if (type == null || type == ERROR || type == NOTHING || type == NULL || isCoreScalar(type)) {
            return type;
        }
        if (type instanceof Frozen) {
            return type;
        }
        if (type instanceof Union u) {
            List<Type> out = new ArrayList<>(u.members().size());
            for (Type m : u.members()) {
                out.add(frozen(m));
            }
            return union(out);
        }
        return new Frozen(type);
    }

    /** The universal nullable frozen top type {@code frozen any?}. */
    Type FROZEN_ANY = new Frozen(ANY);
    Type NULLABLE_FROZEN_ANY = new Union(List.of(FROZEN_ANY, NULL));

    /** A parameter of a function type : its name is part of the type, since calls bind by name. */
    record Parameter(String name, Type type, boolean optional, boolean keywordOnly,
                     boolean mut, boolean borrow, boolean explicitShared, boolean unique) {
        Parameter(String name, Type type, boolean optional) {
            this(name, type, optional, false, false, false, false, false);
        }

        Parameter(String name, Type type, boolean optional, boolean mut, boolean borrow) {
            this(name, type, optional, false, mut, borrow, false, false);
        }

        Parameter(String name, Type type, boolean optional, boolean mut, boolean borrow, boolean explicitShared) {
            this(name, type, optional, false, mut, borrow, explicitShared, false);
        }

        Parameter(String name, Type type, boolean optional, boolean mut, boolean borrow, boolean explicitShared, boolean unique) {
            this(name, type, optional, false, mut, borrow, explicitShared, unique);
        }

        Parameter withType(Type newType) {
            return new Parameter(name, newType, optional, keywordOnly, mut, borrow, explicitShared, unique);
        }

        Parameter withModifiers(boolean newMut, boolean newBorrow) {
            return new Parameter(name, type, optional, keywordOnly, newMut, newBorrow && !explicitShared && !unique, explicitShared, unique);
        }

        Parameter withModifiers(boolean newMut, boolean newBorrow, boolean newUnique) {
            return new Parameter(name, type, optional, keywordOnly, newMut, newBorrow && !explicitShared && !newUnique,
                    explicitShared && !newUnique, newUnique);
        }

        String format(boolean showInferredShared) {
            boolean showShared = !unique && (explicitShared || (showInferredShared && !borrow));
            return (unique ? "unique " : "")
                    + (mut ? "mut " : "")
                    + (showShared ? "shared " : "")
                    + name
                    + (type == ERROR ? "" : " : " + type)
                    + (optional ? " = ..." : "");
        }

        @Override
        public String toString() {
            return format(false);
        }
    }

    /**
     * A function type. {@code forwardedEffectParams} are the indices of its own callback
     * parameters named in {@code calling} (BIP017-D1); {@code capturedCallings} the names of
     * callback parameters of an enclosing function it captures and forwards the effects of
     * ({@code fn calling (f, g) (a : A) : C} returned by {@code compose}), which a call of that
     * enclosing function replaces by the effects of its arguments (BIP017-D5).
     */
    record Function(List<TypeVar> typeParameters, List<Parameter> parameters, Type result,
                    EffectSet effects, Set<Integer> forwardedEffectParams, Set<String> capturedCallings) implements Type {
        public Function {
            effects = effects != null ? effects : EffectSet.EMPTY;
            forwardedEffectParams = forwardedEffectParams == null || forwardedEffectParams.isEmpty()
                    ? Set.of() : Set.copyOf(forwardedEffectParams);
            capturedCallings = capturedCallings == null || capturedCallings.isEmpty()
                    ? Set.of() : java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(capturedCallings));
        }

        Function(List<TypeVar> typeParameters, List<Parameter> parameters, Type result,
                 EffectSet effects, Set<Integer> forwardedEffectParams) {
            this(typeParameters, parameters, result, effects, forwardedEffectParams, Set.of());
        }

        /** This type with other captured callbacks (and the same everything else). */
        Function withCapturedCallings(EffectSet newEffects, Set<String> newCaptured) {
            return new Function(typeParameters, parameters, result, newEffects, forwardedEffectParams, newCaptured);
        }

        /** The names of the enclosing function's parameters that {@code type} (a result) forwards. */
        static Set<String> capturedCallingsOf(Type type) {
            Set<String> names = new java.util.TreeSet<>();
            for (Type member : membersOf(type)) {
                if (member instanceof Function fn) {
                    names.addAll(fn.capturedCallings());
                    names.addAll(capturedCallingsOf(fn.result()));
                }
            }
            return names;
        }

        Function(List<Parameter> parameters, Type result) {
            this(List.of(), parameters, result, EffectSet.EMPTY, Set.of());
        }

        Function(List<Parameter> parameters, Type result, EffectSet effects) {
            this(List.of(), parameters, result, effects, Set.of());
        }

        Function(List<Parameter> parameters, Type result, EffectSet effects, Set<Integer> forwardedEffectParams) {
            this(List.of(), parameters, result, effects, forwardedEffectParams);
        }

        Function(List<TypeVar> typeParameters, List<Parameter> parameters, Type result) {
            this(typeParameters, parameters, result, EffectSet.EMPTY, Set.of());
        }

        Function(List<TypeVar> typeParameters, List<Parameter> parameters, Type result, EffectSet effects) {
            this(typeParameters, parameters, result, effects, Set.of());
        }

        Function withEffects(EffectSet newEffects) {
            return new Function(typeParameters, parameters, result, newEffects, forwardedEffectParams, capturedCallings);
        }

        Function withEffects(EffectSet newEffects, List<Parameter> newParameters) {
            return new Function(typeParameters, newParameters, result, newEffects, forwardedEffectParams, capturedCallings);
        }

        /** The names a {@code calling} clause lists : own callback parameters, then captured ones. */
        List<String> callingNames() {
            List<String> names = new ArrayList<>(forwardedEffectParams.stream()
                    .sorted()
                    .filter(i -> i >= 0 && i < parameters.size())
                    .map(i -> parameters.get(i).name())
                    .toList());
            names.addAll(capturedCallings);
            return names;
        }

        private String formatCallingClause() {
            List<String> names = callingNames();
            if (names.isEmpty()) {
                return "";
            }
            return names.size() == 1 ? "calling " + names.get(0) + " "
                    : "calling (" + String.join(", ", names) + ") ";
        }

        /** Formats this function type highlighting guarantees relevant to {@code expected}. */
        String describeAgainst(Function expected) {
            java.util.EnumSet<org.babblelang.effects.Effect> shown = EffectSet.copySet(effects.declared());
            for (org.babblelang.effects.Effect req : expected.effects().effects()) {
                if (effects.has(req)) {
                    shown.add(req);
                }
            }
            boolean showIO = effects.isIO() || (expected.effects().isHermetic() && !effects.isHermetic());
            boolean showVolatile = !showIO && (effects.explicitVolatile()
                    || (expected.effects().isDeterministic() && effects.isHermetic() && !effects.isDeterministic()));
            boolean showSharedThis = effects.explicitSharedThis()
                    || (expected.effects().borrowThis() && !effects.borrowThis());
            boolean showUniqueReturn = effects.explicitUniqueResult()
                    || (expected.effects().uniqueResult() && effects.uniqueResult());
            String prefix = EffectSet.formatSurface(shown, showIO, showVolatile, effects.mutThis(), showSharedThis)
                    + formatCallingClause();
            String tp = typeParameters.isEmpty() ? ""
                    : typeParameters.stream().map(TypeVar::name).collect(Collectors.joining(", ", "(", ") "));
            StringBuilder params = new StringBuilder("(");
            boolean emittedKwSep = false;
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) {
                    params.append(", ");
                }
                Parameter p = parameters.get(i);
                if (p.keywordOnly() && !emittedKwSep) {
                    params.append("..., ");
                    emittedKwSep = true;
                }
                boolean showShared = i < expected.parameters().size() && expected.parameters().get(i).borrow();
                params.append(p.format(showShared));
            }
            params.append(")");
            return prefix + tp + params
                    + (result == ERROR ? "" : " : " + (showUniqueReturn ? "unique " : "") + result);
        }

        @Override
        public String toString() {
            String tp = typeParameters.isEmpty() ? ""
                    : typeParameters.stream().map(TypeVar::name).collect(Collectors.joining(", ", "(", ") "));
            StringBuilder params = new StringBuilder("(");
            boolean emittedKwSep = false;
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) {
                    params.append(", ");
                }
                Parameter p = parameters.get(i);
                if (p.keywordOnly() && !emittedKwSep) {
                    params.append("..., ");
                    emittedKwSep = true;
                }
                params.append(p);
            }
            params.append(")");
            return effects.formatDeclaredPrefix() + formatCallingClause() + tp
                    + params
                    + (result == ERROR ? "" : " : " + (effects.explicitUniqueResult() ? "unique " : "") + result);
        }
    }

    /** An instance of a class, object literal or interface : nominal. */
    record Instance(TypeNode node, List<Type> typeArguments) implements Type {
        Instance(TypeNode node) {
            this(node, List.of());
        }

        @Override
        public String toString() {
            return typeArguments.isEmpty() ? node.name
                    : node.name + typeArguments.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")"));
        }
    }

    /**
     * The value of {@code def name = type T} (a type alias, BIP023) : what annotations naming it
     * stand for, generic over {@code typeParameters}. Not a value programs can use.
     */
    record TypeAlias(String name, List<TypeVar> typeParameters, Type aliased) implements Type {
        @Override
        public String toString() {
            return "type " + aliased;
        }
    }

    /** The value of a class or interface expression : its constructor and statics, optionally specialized via {@code of}. */
    record ClassValue(TypeNode node, List<Type> typeArguments) implements Type {
        ClassValue(TypeNode node) {
            this(node, List.of());
        }

        @Override
        public String toString() {
            String base = (node.isInterface() ? "interface " : "class ") + node.name;
            return typeArguments.isEmpty() ? base
                    : base + typeArguments.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")"));
        }
    }

    /** A core collection constructor value ({@code list}, {@code list of int}, {@code map of (string, int)}, etc.). */
    record CoreCollection(String kind, List<Type> typeArguments) implements Type {
        CoreCollection(String kind) {
            this(kind, List.of());
        }

        @Override
        public String toString() {
            return typeArguments.isEmpty() ? kind
                    : kind + typeArguments.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")"));
        }
    }

    /** The {@code of} factory of an array type ({@code array.of} or {@code (array of T).of}, BIP020 §2.3). */
    record ArrayOfFactory(Type element) implements Type {
        @Override
        public String toString() {
            return element == null ? "array.of" : "(" + new Array(element) + ").of";
        }
    }

    /** A module imported statically : its exports are its members (docs/design/bip005_modules.md §8.2). */
    record Module(ModuleInterface module) implements Type {
        @Override
        public String toString() {
            return "module " + module.name();
        }
    }

    record JavaPackage(String name) implements Type {
        @Override
        public String toString() {
            return "package " + name;
        }
    }

    /** A Java class as a value : its constructors, static methods and member classes, optionally specialized via {@code of}. */
    record JavaClassValue(Class<?> type, List<Type> typeArguments) implements Type {
        JavaClassValue(Class<?> type) {
            this(type, List.of());
        }

        @Override
        public String toString() {
            String base = "class " + type.getName();
            return typeArguments.isEmpty() ? base
                    : base + typeArguments.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")"));
        }
    }

    /** An instance of a Java class, as a constructor returns it. */
    record JavaInstance(Class<?> type, List<Type> typeArguments) implements Type {
        JavaInstance(Class<?> type) {
            this(type, List.of());
        }

        @Override
        public String toString() {
            String name = coreCollectionDisplayName(type);
            return typeArguments.isEmpty() ? name
                    : name + typeArguments.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")"));
        }

        private static String coreCollectionDisplayName(Class<?> type) {
            if (type == java.util.List.class) {
                return "list";
            }
            if (type == java.util.Set.class) {
                return "set";
            }
            if (type == java.util.SortedSet.class) {
                return "sortedset";
            }
            if (type == java.util.Map.class) {
                return "map";
            }
            if (type == java.util.SortedMap.class) {
                return "sortedmap";
            }
            if (type == java.util.Map.Entry.class) {
                return "entry";
            }
            return type.getName();
        }
    }

    /** The methods of a Java class with one name, selected through the class or an instance. */
    record JavaMethod(Class<?> owner, List<Type> typeArguments, String name, List<Type> methodTypeArguments) implements Type {
        JavaMethod(Class<?> owner, String name) {
            this(owner, List.of(), name, List.of());
        }

        JavaMethod(Class<?> owner, List<Type> typeArguments, String name) {
            this(owner, typeArguments, name, List.of());
        }

        @Override
        public String toString() {
            return owner.getName() + "." + name;
        }
    }

    /** {@code print}, {@code println}, {@code affiche}, {@code afficherc} : any number of {@code any?}, optional {@code to : Appendable}. */
    record Print() implements Type {
        @Override
        public String toString() {
            return "print";
        }
    }

    Print PRINT = new Print();

    static Type nullable(Type type) {
        return union(type, NULL);
    }

    static Type union(Type... types) {
        return union(Arrays.asList(types));
    }

    static Type union(Collection<? extends Type> types) {
        Set<Type> nonNull = new LinkedHashSet<>();
        boolean hasNull = false;
        boolean hasAny = false;
        boolean hasFrozenAny = false;
        for (Type t : types) {
            if (t == ERROR) {
                return ERROR;
            }
            if (t instanceof Union u) {
                for (Type member : u.members()) {
                    if (member == ERROR) {
                        return ERROR;
                    }
                    if (member == NULL) {
                        hasNull = true;
                    } else if (member == ANY) {
                        hasAny = true;
                    } else if (member instanceof Frozen f && f.of() == ANY) {
                        hasFrozenAny = true;
                    } else if (member != NOTHING) {
                        nonNull.add(member);
                    }
                }
            } else if (t == NULL) {
                hasNull = true;
            } else if (t == ANY) {
                hasAny = true;
            } else if (t instanceof Frozen f && f.of() == ANY) {
                hasFrozenAny = true;
            } else if (t != NOTHING) {
                nonNull.add(t);
            }
        }
        if (hasAny) {
            nonNull.removeIf(m -> !(m instanceof Frozen));
            if (nonNull.isEmpty() && !hasFrozenAny) {
                return hasNull ? NULLABLE_ANY : ANY;
            }
            Set<Type> combined = new LinkedHashSet<>();
            combined.add(ANY);
            if (hasFrozenAny) {
                combined.add(FROZEN_ANY);
            } else {
                combined.addAll(nonNull);
            }
            nonNull = combined;
        } else if (hasFrozenAny) {
            nonNull.removeIf(m -> m instanceof Frozen);
            nonNull.add(FROZEN_ANY);
        }
        if (nonNull.isEmpty()) {
            return hasNull ? NULL : NOTHING;
        }
        if (!hasNull && nonNull.size() == 1) {
            return nonNull.iterator().next();
        }
        List<Type> members = new ArrayList<>(nonNull.size() + (hasNull ? 1 : 0));
        members.addAll(nonNull);
        if (hasNull) {
            members.add(NULL);
        }
        return new Union(List.copyOf(members));
    }

    static boolean containsNull(Type type) {
        return type == NULL || (type instanceof Union union && union.members().contains(NULL));
    }

    static Type withoutNull(Type type) {
        if (type == NULL) {
            return NOTHING;
        }
        if (type instanceof Union union) {
            List<Type> filtered = new ArrayList<>(union.members().size());
            for (Type member : union.members()) {
                if (member != NULL) {
                    filtered.add(member);
                }
            }
            return union(filtered);
        }
        return type;
    }

    static List<Type> membersOf(Type type) {
        return type instanceof Union union ? union.members() : List.of(type);
    }

    /** Whether this type mentions a type variable. */
    default boolean hasTypeVar() {
        return anyMatch(Type.TypeVar.class::isInstance);
    }

    /** Whether this type mentions a synthetic type variable (one a call is still solving, with no owner). */
    default boolean hasSyntheticTypeVar() {
        return anyMatch(t -> t instanceof Type.TypeVar tv && tv.owner() == null);
    }

    /** Structural predicate catamorphism over this type and all nested type children. */
    default boolean anyMatch(Predicate<Type> predicate) {
        if (predicate.test(this)) {
            return true;
        }
        return switch (this) {
            case Union u -> {
                for (Type m : u.members()) {
                    if (m.anyMatch(predicate)) {
                        yield true;
                    }
                }
                yield false;
            }
            case Intersection i -> {
                for (Type m : i.members()) {
                    if (m.anyMatch(predicate)) {
                        yield true;
                    }
                }
                yield false;
            }
            case Frozen f -> f.of().anyMatch(predicate);
            case Array a -> a.element().anyMatch(predicate);
            case Function fn -> {
                for (Parameter p : fn.parameters()) {
                    if (p.type().anyMatch(predicate)) {
                        yield true;
                    }
                }
                yield fn.result().anyMatch(predicate);
            }
            case Instance inst -> {
                for (Type a : inst.typeArguments()) {
                    if (a.anyMatch(predicate)) {
                        yield true;
                    }
                }
                yield false;
            }
            case JavaInstance ji -> {
                for (Type a : ji.typeArguments()) {
                    if (a.anyMatch(predicate)) {
                        yield true;
                    }
                }
                yield false;
            }
            case CoreCollection cc -> {
                for (Type a : cc.typeArguments()) {
                    if (a.anyMatch(predicate)) {
                        yield true;
                    }
                }
                yield false;
            }
            case JavaMethod jm -> {
                for (Type a : jm.typeArguments()) {
                    if (a.anyMatch(predicate)) {
                        yield true;
                    }
                }
                yield false;
            }
            case ArrayOfFactory af -> af.element() != null && af.element().anyMatch(predicate);
            case Simple ignored -> false;
            case TypeVar ignored -> false;
            case ClassValue ignored -> false;
            case TypeAlias ignored -> false;
            case Module ignored -> false;
            case JavaPackage ignored -> false;
            case JavaClassValue ignored -> false;
            case Print ignored -> false;
        };
    }

    /**
     * Top-down structural map catamorphism: {@code mapper.apply(node)} returns a replacement
     * to short-circuit, or {@code null} to descend into children. Preserves physical identity
     * when no child changes.
     */
    default Type mapTypes(UnaryOperator<Type> mapper) {
        Type direct = mapper.apply(this);
        if (direct != null) {
            return direct;
        }
        return switch (this) {
            case Union u -> {
                List<Type> src = u.members();
                List<Type> out = null;
                for (int i = 0; i < src.size(); i++) {
                    Type orig = src.get(i);
                    Type mapped = orig.mapTypes(mapper);
                    if (out != null) {
                        out.add(mapped);
                    } else if (mapped != orig) {
                        out = new ArrayList<>(src.size());
                        for (int j = 0; j < i; j++) {
                            out.add(src.get(j));
                        }
                        out.add(mapped);
                    }
                }
                yield out != null ? union(out) : u;
            }
            case Intersection i -> {
                List<Type> mapped = mapList(i.members(), mapper);
                yield mapped != i.members() ? intersection(mapped) : i;
            }
            case Frozen f -> {
                Type inner = f.of().mapTypes(mapper);
                yield inner != f.of() ? frozen(inner) : f;
            }
            case Array a -> {
                Type elem = a.element().mapTypes(mapper);
                yield elem != a.element() ? new Array(elem) : a;
            }
            case Function fn -> {
                List<Parameter> src = fn.parameters();
                List<Parameter> out = null;
                for (int i = 0; i < src.size(); i++) {
                    Parameter orig = src.get(i);
                    Type mappedType = orig.type().mapTypes(mapper);
                    if (out != null) {
                        out.add(mappedType != orig.type() ? orig.withType(mappedType) : orig);
                    } else if (mappedType != orig.type()) {
                        out = new ArrayList<>(src.size());
                        for (int j = 0; j < i; j++) {
                            out.add(src.get(j));
                        }
                        out.add(orig.withType(mappedType));
                    }
                }
                Type res = fn.result().mapTypes(mapper);
                yield (out != null || res != fn.result())
                        ? new Function(fn.typeParameters(), out != null ? out : src, res, fn.effects(),
                                fn.forwardedEffectParams(), fn.capturedCallings())
                        : fn;
            }
            case Instance inst -> {
                List<Type> mapped = mapList(inst.typeArguments(), mapper);
                yield mapped != inst.typeArguments() ? new Instance(inst.node(), mapped) : inst;
            }
            case JavaInstance ji -> {
                List<Type> mapped = mapList(ji.typeArguments(), mapper);
                yield mapped != ji.typeArguments() ? new JavaInstance(ji.type(), mapped) : ji;
            }
            case JavaMethod jm -> {
                List<Type> mapped = mapList(jm.typeArguments(), mapper);
                yield mapped != jm.typeArguments() ? new JavaMethod(jm.owner(), mapped, jm.name()) : jm;
            }
            case ArrayOfFactory af -> {
                if (af.element() == null) {
                    yield af;
                }
                Type mapped = af.element().mapTypes(mapper);
                yield mapped != af.element() ? new ArrayOfFactory(mapped) : af;
            }
            default -> this;
        };
    }

    private static List<Type> mapList(List<Type> src, UnaryOperator<Type> mapper) {
        if (src.isEmpty()) {
            return src;
        }
        List<Type> out = null;
        for (int i = 0; i < src.size(); i++) {
            Type orig = src.get(i);
            Type mapped = orig.mapTypes(mapper);
            if (out != null) {
                out.add(mapped);
            } else if (mapped != orig) {
                out = new ArrayList<>(src.size());
                for (int j = 0; j < i; j++) {
                    out.add(src.get(j));
                }
                out.add(mapped);
            }
        }
        return out != null ? out : src;
    }
}
