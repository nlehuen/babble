package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Persistent lexical occurrence-typing environment ({@code docs/design/bip010_type-checker-simplification.md} §4.4):
 * wraps an immutable map of narrowed types for local {@code def} and parameter declarations,
 * with algebraic {@link Refinement} combinators and explicit {@link #evict}, {@link #evictAll},
 * and {@link #enterCallable} barriers.
 */
final class TypeEnv {
    static final TypeEnv EMPTY = new TypeEnv(Collections.emptyMap());

    record Refinement(Map<ParserRuleContext, Type> whenTrue, Map<ParserRuleContext, Type> whenFalse) {
        static final Refinement EMPTY = new Refinement(Map.of(), Map.of());

        Refinement invert() {
            return new Refinement(whenFalse, whenTrue);
        }

        Refinement and(Refinement other, java.util.function.BinaryOperator<Type> combiner) {
            return new Refinement(merge(whenTrue, other.whenTrue, combiner), Map.of());
        }

        Refinement or(Refinement other, java.util.function.BinaryOperator<Type> combiner) {
            return new Refinement(Map.of(), merge(whenFalse, other.whenFalse, combiner));
        }

        private static Map<ParserRuleContext, Type> merge(Map<ParserRuleContext, Type> a,
                                                          Map<ParserRuleContext, Type> b,
                                                          java.util.function.BinaryOperator<Type> combiner) {
            if (a.isEmpty()) {
                return b;
            }
            if (b.isEmpty()) {
                return a;
            }
            Map<ParserRuleContext, Type> merged = new IdentityHashMap<>(a);
            for (Map.Entry<ParserRuleContext, Type> entry : b.entrySet()) {
                merged.merge(entry.getKey(), entry.getValue(), combiner);
            }
            return Collections.unmodifiableMap(merged);
        }
    }

    private final Map<ParserRuleContext, Type> narrowings;

    private TypeEnv(Map<ParserRuleContext, Type> narrowings) {
        this.narrowings = narrowings;
    }

    Type lookup(ParserRuleContext declaration) {
        return narrowings.get(declaration);
    }

    TypeEnv apply(Map<ParserRuleContext, Type> refinements) {
        if (refinements == null || refinements.isEmpty()) {
            return this;
        }
        Map<ParserRuleContext, Type> next = new IdentityHashMap<>(narrowings);
        next.putAll(refinements);
        return new TypeEnv(Collections.unmodifiableMap(next));
    }

    TypeEnv evict(ParserRuleContext declaration) {
        if (declaration == null || !narrowings.containsKey(declaration)) {
            return this;
        }
        Map<ParserRuleContext, Type> next = new IdentityHashMap<>(narrowings);
        next.remove(declaration);
        return next.isEmpty() ? EMPTY : new TypeEnv(Collections.unmodifiableMap(next));
    }

    TypeEnv evictAll(Set<ParserRuleContext> declarations) {
        if (narrowings.isEmpty() || declarations == null || declarations.isEmpty()) {
            return this;
        }
        boolean changed = false;
        Map<ParserRuleContext, Type> next = null;
        for (ParserRuleContext decl : narrowings.keySet()) {
            if (declarations.contains(decl)) {
                if (!changed) {
                    next = new IdentityHashMap<>(narrowings);
                    changed = true;
                }
                next.remove(decl);
            }
        }
        if (!changed) {
            return this;
        }
        return next.isEmpty() ? EMPTY : new TypeEnv(Collections.unmodifiableMap(next));
    }

    TypeEnv enterCallable(Set<ParserRuleContext> reassigned) {
        return evictAll(reassigned);
    }

    /**
     * Joins two post-branch environments: evicts any declaration whose narrowing was killed
     * in either branch relative to {@code base}.
     */
    static TypeEnv joinBranches(TypeEnv base, TypeEnv branchA, TypeEnv branchB) {
        if (base.narrowings.isEmpty()) {
            return EMPTY;
        }
        Map<ParserRuleContext, Type> joined = null;
        for (ParserRuleContext decl : base.narrowings.keySet()) {
            if (branchA.narrowings.get(decl) == null || branchB.narrowings.get(decl) == null) {
                if (joined == null) {
                    joined = new IdentityHashMap<>(base.narrowings);
                }
                joined.remove(decl);
            }
        }
        if (joined == null) {
            return base;
        }
        return joined.isEmpty() ? EMPTY : new TypeEnv(Collections.unmodifiableMap(joined));
    }
}
