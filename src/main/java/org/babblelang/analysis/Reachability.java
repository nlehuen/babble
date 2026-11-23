package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ANTLR-free reachability, alias provenance, and path-identity domain for static
 * {@code mut} / {@code borrow} / {@code unique} / exclusivity checking ({@code BIP027-I3}).
 */
final class Reachability {
    private Reachability() {
    }

    static final Object THIS_KEY = new Object();

    /**
     * What a value reaches : parameters (by index), {@code this}, or state external to the frame.
     * {@link Origin} holds one for the value itself and, under the shallow modality, one for the
     * elements of a fresh local container.
     */
    record Provenance(BitSet params, boolean aliasesThis, boolean external) {
        static final Provenance EMPTY = new Provenance(Origin.EMPTY_BITS, false, false);

        boolean isEmpty() {
            return params.isEmpty() && !aliasesThis && !external;
        }

        Provenance union(Provenance other) {
            if (other == null || other.isEmpty()) {
                return this;
            }
            if (isEmpty()) {
                return other;
            }
            BitSet merged = (BitSet) params.clone();
            merged.or(other.params);
            return new Provenance(merged, aliasesThis || other.aliasesThis, external || other.external);
        }
    }

    /** Tracks alias provenance and direct binding identity for static mut/borrow/exclusivity checks. */
    static final class Origin {
        static final BitSet EMPTY_BITS = new BitSet(0);
        static final Origin IMMUTABLE = new Origin(EMPTY_BITS, false, false, false, null, false, null, Set.of(), null);
        static final Origin MOVED = new Origin(EMPTY_BITS, false, false, false, null, true, null, Set.of(), null);

        final BitSet params;
        final boolean aliasesThis;
        final boolean external;
        final boolean unique;
        /**
         * The shallow modality (BIP016-D9, {@code BIP027-I5}) : non-null for a fresh local allocation whose
         * own shell has not escaped (even after storing non-unique elements), holding what its elements
         * reach — propagated on reads, deep mutations, or escapes, never to the shell itself.
         */
        final Provenance shallow;
        final boolean moved;
        /** Owning local/param declaration when {@link #owned()} and bound to a live variable, or {@code null} for a fresh rvalue. */
        final Object uniqueOwner;
        /** Direct local/param/this identity keys when an expression is a direct alias (e.g. {@code def y = x}). */
        final Set<Object> directKeys;
        /** Pure member path key when an expression is {@code base.f1.f2} without calls. */
        final PathKey pathKey;

        Origin(BitSet params, boolean aliasesThis, boolean external, boolean unique, Provenance shallow, boolean moved,
               Object uniqueOwner, Set<Object> directKeys, PathKey pathKey) {
            this.params = params;
            this.aliasesThis = aliasesThis;
            this.external = external;
            this.unique = unique;
            this.shallow = shallow;
            this.moved = moved;
            this.uniqueOwner = uniqueOwner;
            this.directKeys = directKeys;
            this.pathKey = pathKey;
        }

        Origin(BitSet params, boolean aliasesThis, boolean external, Set<Object> directKeys, PathKey pathKey) {
            this(params, aliasesThis, external, false, null, false, null, directKeys, pathKey);
        }

        static Origin ofParam(int index, Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            BitSet bits = new BitSet();
            bits.set(index);
            return new Origin(bits, false, false, false, null, false, null, Set.of(key), new PathKey(key, List.of()));
        }

        static Origin ofUniqueParam(int index, Object key, boolean immutable, boolean borrowed) {
            if (immutable) {
                return IMMUTABLE;
            }
            BitSet bits = new BitSet();
            if (borrowed) {
                bits.set(index);
            }
            return new Origin(bits, false, false, true, borrowed ? null : Provenance.EMPTY, false, key,
                    Set.of(key), new PathKey(key, List.of()));
        }

        static Origin ofUnique(Object key, Object uniqueOwner, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            Set<Object> keys = key != null ? Set.of(key) : Set.of();
            PathKey pk = key != null ? new PathKey(key, List.of()) : null;
            return new Origin(new BitSet(0), false, false, true, Provenance.EMPTY, false, uniqueOwner, keys, pk);
        }

        static Origin ofThis(Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            return new Origin(new BitSet(0), true, false, false, null, false, null, Set.of(key), new PathKey(key, List.of()));
        }

        static Origin ofExternal(Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            Set<Object> keys = key != null ? Set.of(key) : Set.of();
            PathKey pk = key != null ? new PathKey(key, List.of()) : null;
            return new Origin(new BitSet(0), false, true, false, null, false, null, keys, pk);
        }

        /** A fresh local container whose shell has not escaped (the shallow modality). */
        boolean localFresh() {
            return shallow != null;
        }

        /** The uniqueness axis : {@code unique}, or a fresh local shell. */
        boolean owned() {
            return unique || shallow != null;
        }

        /** What the elements of a fresh local container reach (empty otherwise). */
        Provenance elements() {
            return shallow != null ? shallow : Provenance.EMPTY;
        }

        boolean isEmpty() {
            return params.isEmpty() && !aliasesThis && !external && !unique && shallow == null && !moved;
        }

        boolean hasElementTaint() {
            return shallow != null && !shallow.isEmpty();
        }

        /** Direct provenance joined with the elements' (for reads, deep mutations, or escapes). */
        private Provenance deep(Origin taint) {
            Provenance p = new Provenance(params, aliasesThis, external).union(elements());
            if (taint != null && taint != IMMUTABLE) {
                p = p.union(new Provenance(taint.params, taint.aliasesThis, taint.external)).union(taint.elements());
            }
            return p;
        }

        /** Flattens element provenance into direct provenance (for reads, deep mutations, or escapes). */
        Origin flattenElements() {
            if (!hasElementTaint()) {
                return this;
            }
            Provenance flat = deep(null);
            return new Origin((BitSet) flat.params().clone(), flat.aliasesThis(), flat.external(),
                    false, null, moved, null, directKeys, pathKey);
        }

        Origin deriveMember(String field, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            PathKey nextPath = pathKey != null ? pathKey.append(field) : null;
            return derived(Set.of(), nextPath);
        }

        Origin stripDirectIdentity(boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            return derived(Set.of(), null);
        }

        private Origin derived(Set<Object> keys, PathKey path) {
            if (hasElementTaint()) {
                Provenance flat = deep(null);
                return new Origin((BitSet) flat.params().clone(), flat.aliasesThis(), flat.external(),
                        false, null, false, null, keys, path);
            }
            return new Origin((BitSet) params.clone(), aliasesThis, external, unique, null, false,
                    unique ? uniqueOwner : null, keys, path);
        }

        /** Stores a non-unique element into a fresh local container: drops {@code unique} while keeping the shell fresh (BIP016-D9). */
        Origin absorbElement(Origin taint) {
            Provenance elems = elements();
            if (taint != null && taint != IMMUTABLE) {
                elems = elems.union(new Provenance(taint.params, taint.aliasesThis, taint.external)).union(taint.elements());
            }
            return new Origin((BitSet) params.clone(), aliasesThis, external, false, cloned(elems), false, uniqueOwner,
                    directKeys, pathKey);
        }

        Origin withoutUnique() {
            return demoteAlias(null);
        }

        Origin demoteAlias(Origin taint) {
            Provenance flat = deep(taint);
            return new Origin((BitSet) flat.params().clone(), flat.aliasesThis(), flat.external(),
                    false, null, false, null, directKeys, pathKey);
        }

        Origin demoteToExternal(Origin taint) {
            Provenance flat = deep(taint);
            return new Origin((BitSet) flat.params().clone(), flat.aliasesThis(), true,
                    false, null, false, null, directKeys, pathKey);
        }

        Origin join(Origin other) {
            if (this.moved || other.moved) {
                return MOVED;
            }
            if (this == IMMUTABLE) {
                return other.withoutExactSingleKey();
            }
            if (other == IMMUTABLE) {
                return this.withoutExactSingleKey();
            }
            BitSet merged = (BitSet) params.clone();
            merged.or(other.params);
            Provenance mergedElems = this.elements().union(other.elements());
            Set<Object> keys;
            if (directKeys.isEmpty() && other.directKeys.isEmpty()) {
                keys = Set.of();
            } else {
                Set<Object> union = new HashSet<>(directKeys);
                union.addAll(other.directKeys);
                keys = Set.copyOf(union);
            }
            PathKey pk = Objects.equals(pathKey, other.pathKey) ? pathKey : null;
            boolean mergedThis = aliasesThis || other.aliasesThis;
            boolean confined = !this.external && !other.external && !mergedThis && merged.isEmpty();
            boolean mergedUnique = this.unique && other.unique && confined;
            boolean mergedLocalFresh = this.localFresh() && other.localFresh() && confined;
            Object mergedOwner = (mergedUnique || mergedLocalFresh) && Objects.equals(this.uniqueOwner, other.uniqueOwner)
                    ? this.uniqueOwner : null;
            boolean mergedExternal = this.external || other.external
                    || (!mergedUnique && !mergedLocalFresh && (this.owned() || other.owned()) && confined);
            if (!mergedLocalFresh && !mergedElems.isEmpty()) {
                merged.or(mergedElems.params());
                return new Origin(merged, mergedThis || mergedElems.aliasesThis(), mergedExternal || mergedElems.external(),
                        false, null, false, null, keys, pk);
            }
            return new Origin(merged, mergedThis, mergedExternal, mergedUnique,
                    mergedLocalFresh ? cloned(mergedElems) : null, false, mergedOwner, keys, pk);
        }

        Origin widen(Origin other) {
            Origin joined = this.join(other);
            if (joined == MOVED || joined == IMMUTABLE) {
                return joined;
            }
            PathKey widenedPath = this.pathKey != null && other.pathKey != null
                    ? this.pathKey.widen(other.pathKey) : null;
            if (Objects.equals(widenedPath, joined.pathKey)) {
                return joined;
            }
            return new Origin((BitSet) joined.params.clone(), joined.aliasesThis, joined.external,
                    joined.unique, cloned(joined.shallow), joined.moved, joined.uniqueOwner,
                    joined.directKeys, widenedPath);
        }

        private Origin withoutExactSingleKey() {
            return new Origin((BitSet) params.clone(), aliasesThis, external, unique, cloned(shallow), moved, uniqueOwner,
                    directKeys, pathKey);
        }

        private static Provenance cloned(Provenance p) {
            return p == null || p.params().isEmpty() ? p
                    : new Provenance((BitSet) p.params().clone(), p.aliasesThis(), p.external());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Origin that)) {
                return false;
            }
            return aliasesThis == that.aliasesThis
                    && external == that.external
                    && unique == that.unique
                    && moved == that.moved
                    && Objects.equals(shallow, that.shallow)
                    && Objects.equals(uniqueOwner, that.uniqueOwner)
                    && params.equals(that.params)
                    && directKeys.equals(that.directKeys)
                    && Objects.equals(pathKey, that.pathKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(params, aliasesThis, external, unique, shallow, moved, uniqueOwner, directKeys, pathKey);
        }
    }

    /** Depth bound {@code k = 3} for access-path widening ({@code BIP027-I4}, §3.1). */
    static final int MAX_PATH_DEPTH = 3;

    record PathKey(Object root, List<String> segments, boolean truncated) {
        PathKey(Object root, List<String> segments) {
            this(root, segments, false);
        }

        PathKey append(String field) {
            if (truncated || segments.size() >= MAX_PATH_DEPTH) {
                return new PathKey(root, segments, true);
            }
            List<String> next = new ArrayList<>(segments.size() + 1);
            next.addAll(segments);
            next.add(field);
            return new PathKey(root, List.copyOf(next), false);
        }

        /** Widens two access paths to their longest common prefix (bounded height <= MAX_PATH_DEPTH). */
        PathKey widen(PathKey other) {
            if (other == null || !Objects.equals(root, other.root)) {
                return null;
            }
            int limit = Math.min(segments.size(), other.segments.size());
            int i = 0;
            while (i < limit && segments.get(i).equals(other.segments.get(i))) {
                i++;
            }
            if (i == segments.size() && i == other.segments.size() && truncated == other.truncated) {
                return this;
            }
            return new PathKey(root, List.copyOf(segments.subList(0, i)), true);
        }

        /**
         * Prefix-aware path overlap ({@code BIP027-D5}): {@code pair} overlaps {@code pair.a}
         * and {@code pair.a.x} overlaps {@code pair.a}, whereas disjoint sibling fields
         * ({@code pair.a} vs {@code pair.b}) do not overlap.
         */
        boolean overlaps(PathKey other) {
            if (other == null || !Objects.equals(root, other.root)) {
                return false;
            }
            int minLen = Math.min(segments.size(), other.segments.size());
            for (int i = 0; i < minLen; i++) {
                if (!segments.get(i).equals(other.segments.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    static boolean staticallySameObject(Origin a, Origin b) {
        if (a == Origin.IMMUTABLE || b == Origin.IMMUTABLE) {
            return false;
        }
        if (!a.directKeys.isEmpty() && !b.directKeys.isEmpty()) {
            for (Object k : a.directKeys) {
                if (b.directKeys.contains(k)) {
                    return true;
                }
            }
        }
        if (a.pathKey != null && b.pathKey != null && a.pathKey.overlaps(b.pathKey)) {
            return true;
        }
        if (a.pathKey != null && b.directKeys.contains(a.pathKey.root())) {
            return true;
        }
        return b.pathKey != null && a.directKeys.contains(b.pathKey.root());
    }
}
