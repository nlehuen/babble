package org.babblelang.analysis;

import org.babblelang.runtime.ClassShape;

import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Modal kinds of types by mode crossing ({@code BIP027-P5}, docs/design/bip027_tackling-complexity-in-the-compiler.md
 * §3.3, after Peters et al., <i>Mode Crossing</i>, ICFP 2026) : the one place that decides whether
 * a value of a type is deeply immutable, for the type checker and the effect checker alike.
 *
 * <p>A type's {@link ModalKind} says which {@link Axis modal axes} its values cross, provided the
 * type variables it {@linkplain ModalKind#deps() depends on} are instantiated with types that cross
 * them too. Core scalars, {@code null}, {@code frozen T} cross everything ; a union crosses what all
 * its members cross ; a Java class the axioms say is {@code immutable} crosses what its type
 * arguments cross ; an {@code object} with no fields crosses everything ; a record ({@code value}
 * class) crosses what its fields' types cross, so its kind is a dependency on its own type
 * parameters — computed once per {@link TypeNode} and cached, rather than walking the fields of
 * every record at every question.
 *
 * <p>The cache is per {@link TypeNode}, and a node's fields only settle once slot inference has
 * converged : {@link #setCaching} turns it on for the phases that run on settled types (effect
 * inference and verification), and every change of phase drops what it holds.
 *
 * <p>ANTLR-free.
 */
final class ModalKindTable {

    /** A modal axis a type may cross (OxCaml's modes, BIP027 §3.3). */
    enum Axis {
        /** Shared reads are safe : the value is deeply immutable ({@code frozen}, BIP019 mutable reads). */
        CONTENTION,
        /** The value may cross threads : BIP007 §10's {@code threadsafe}, a single modal bit. */
        PORTABILITY
    }

    /**
     * The axes a type crosses, and the type variables that must cross them for it to : an
     * instance's kind substitutes its type arguments for its node's {@code deps}.
     */
    record ModalKind(Set<Axis> crossed, Set<Type.TypeVar> deps) {
        static final ModalKind NEVER = new ModalKind(Set.of(), Set.of());
        static final ModalKind ALWAYS = new ModalKind(Collections.unmodifiableSet(EnumSet.allOf(Axis.class)), Set.of());

        static ModalKind dependingOn(Set<Type.TypeVar> deps) {
            return deps.isEmpty() ? ALWAYS : new ModalKind(ALWAYS.crossed, Set.copyOf(deps));
        }

        boolean crossesUnconditionally(Axis axis) {
            return crossed.contains(axis) && deps.isEmpty();
        }

        boolean isNever() {
            return crossed.isEmpty();
        }

        /** Both kinds' requirements at once (a union, the fields of a record). */
        ModalKind meet(ModalKind other) {
            if (isNever() || other.isNever()) {
                return NEVER;
            }
            if (other.deps.isEmpty()) {
                return this;
            }
            if (deps.isEmpty()) {
                return other;
            }
            Set<Type.TypeVar> both = new LinkedHashSet<>(deps);
            both.addAll(other.deps);
            return dependingOn(both);
        }
    }

    /** What the table reads of the type checker. */
    interface Types {
        /** Whether the axioms say instances of {@code cls} are deeply immutable when their type arguments are. */
        boolean isJavaClassImmutable(Class<?> cls);

        /** The declared or inferred type of a field of {@code node}, in terms of the node's type parameters ; {@link Type#ERROR} if unknown. */
        Type memberType(TypeNode node, TypeNode.Member member);

        /** The bindings of an instance's node's type parameters to its type arguments. */
        Map<Type.TypeVar, Type> instanceEnv(Type.Instance instance);
    }

    private final Types types;
    private final Map<TypeNode, ModalKind> nodeKinds = new IdentityHashMap<>();
    /** The nodes whose kind is being computed : a record reaching itself assumes it crosses (greatest fixpoint). */
    private final Set<TypeNode> inProgress = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean caching;
    /** Whether the kind being computed relied on the assumption about a node in progress. */
    private boolean assumed;

    ModalKindTable(Types types) {
        this.types = types;
    }

    ModalKindTable(Predicate<Class<?>> javaImmutable, TypeChecker checker) {
        this(new Types() {
            @Override
            public boolean isJavaClassImmutable(Class<?> cls) {
                return javaImmutable.test(cls);
            }

            @Override
            public Type memberType(TypeNode node, TypeNode.Member member) {
                return checker.memberType(node, member);
            }

            @Override
            public Map<Type.TypeVar, Type> instanceEnv(Type.Instance instance) {
                return checker.algebra().instanceEnv(instance);
            }
        });
    }

    /** Turns the per-node cache on (types have settled) or off, dropping what it holds either way. */
    void setCaching(boolean caching) {
        this.caching = caching;
        nodeKinds.clear();
    }

    boolean isDeeplyImmutable(Type type) {
        return crosses(type, Axis.CONTENTION);
    }

    boolean crosses(Type type, Axis axis) {
        return kindOf(type).crossesUnconditionally(axis);
    }

    ModalKind kindOf(Type type) {
        if (type == null || type == Type.ERROR || type == Type.NOTHING || Type.isCoreScalar(type)
                || type == Type.NULL || type instanceof Type.Frozen) {
            return ModalKind.ALWAYS;
        }
        if (type instanceof Type.TypeVar tv) {
            return ModalKind.dependingOn(Set.of(tv));
        }
        if (type instanceof Type.Union u) {
            ModalKind kind = ModalKind.ALWAYS;
            for (Type member : u.members()) {
                kind = kind.meet(kindOf(member));
                if (kind.isNever()) {
                    break;
                }
            }
            return kind;
        }
        if (type instanceof Type.JavaInstance ji) {
            if (!types.isJavaClassImmutable(ji.type())
                    || (ji.type().getTypeParameters().length > 0 && ji.typeArguments().isEmpty())) {
                return ModalKind.NEVER;
            }
            ModalKind kind = ModalKind.ALWAYS;
            for (Type arg : ji.typeArguments()) {
                kind = kind.meet(kindOf(arg));
            }
            return kind;
        }
        if (type instanceof Type.Instance inst) {
            if (inst.node().kind == ClassShape.Kind.OBJECT) {
                return hasFields(inst.node()) ? ModalKind.NEVER : ModalKind.ALWAYS;
            }
            if (inst.node().kind == ClassShape.Kind.VALUE_CLASS) {
                return instantiate(nodeKind(inst.node()), types.instanceEnv(inst));
            }
        }
        return ModalKind.NEVER;
    }

    /** {@code kind} with the type arguments of an instance substituted for its node's type parameters. */
    private ModalKind instantiate(ModalKind kind, Map<Type.TypeVar, Type> env) {
        if (kind.isNever() || kind.deps().isEmpty()) {
            return kind;
        }
        ModalKind result = ModalKind.ALWAYS;
        for (Type.TypeVar dep : kind.deps()) {
            Type arg = env.get(dep);
            result = result.meet(arg != null ? kindOf(arg) : ModalKind.dependingOn(Set.of(dep)));
            if (result.isNever()) {
                break;
            }
        }
        return result;
    }

    /** The kind of a record's instances, in terms of its own type parameters. */
    private ModalKind nodeKind(TypeNode node) {
        ModalKind cached = nodeKinds.get(node);
        if (cached != null) {
            return cached;
        }
        if (inProgress.contains(node)) {
            assumed = true;
            return ModalKind.ALWAYS;
        }
        boolean outerAssumed = assumed;
        assumed = false;
        inProgress.add(node);
        ModalKind kind = ModalKind.ALWAYS;
        try {
            for (TypeNode.Member member : node.members.values()) {
                if (member.isMethod() || member.hasFunction) {
                    continue;
                }
                Type mt = types.memberType(node, member);
                kind = mt == Type.ERROR ? ModalKind.NEVER : kind.meet(kindOf(mt));
                if (kind.isNever()) {
                    break;
                }
            }
        } finally {
            inProgress.remove(node);
        }
        // A kind that relied on an assumption about an enclosing node in progress is only final
        // once that node's is : cache it only if it did not, or if it is the outermost.
        if (caching && (!assumed || inProgress.isEmpty())) {
            nodeKinds.put(node, kind);
        }
        assumed = outerAssumed || (assumed && !inProgress.isEmpty());
        return kind;
    }

    private static boolean hasFields(TypeNode node) {
        for (TypeNode.Member member : node.members.values()) {
            if (!member.isMethod() && !member.hasFunction) {
                return true;
            }
        }
        return false;
    }
}
