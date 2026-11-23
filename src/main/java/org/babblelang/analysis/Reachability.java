package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ANTLR-free reachability, alias provenance, and path-identity domain for static
 * {@code mut} / {@code borrow} / {@code unique} / exclusivity checking ({@code BIP027-P3}).
 */
final class Reachability {
    private Reachability() {
    }

    static final Object THIS_KEY = new Object();

    /** Tracks alias provenance and direct binding identity for static mut/borrow/exclusivity checks. */
    static final class Origin {
        static final BitSet EMPTY_BITS = new BitSet(0);
        static final Origin IMMUTABLE = new Origin(EMPTY_BITS, false, false, false, false, false, null,
                EMPTY_BITS, false, false, Set.of(), null);
        static final Origin MOVED = new Origin(EMPTY_BITS, false, false, false, false, true, null,
                EMPTY_BITS, false, false, Set.of(), null);

        final BitSet params;
        final boolean aliasesThis;
        final boolean external;
        final boolean unique;
        /** True for fresh local allocations (even after storing non-unique elements) whose own shell has not escaped (BIP016-D9). */
        final boolean localFresh;
        final boolean moved;
        /** Owning local/param declaration when {@code unique} or {@code localFresh} is bound to a live variable, or {@code null} for a fresh rvalue. */
        final Object uniqueOwner;
        /** Element provenance stored inside a {@code localFresh} container (propagated on reads, deep mutations, or escapes). */
        final BitSet elementParams;
        final boolean elementThis;
        final boolean elementExternal;
        /** Direct local/param/this identity keys when an expression is a direct alias (e.g. {@code def y = x}). */
        final Set<Object> directKeys;
        /** Pure member path key when an expression is {@code base.f1.f2} without calls. */
        final PathKey pathKey;

        Origin(BitSet params, boolean aliasesThis, boolean external, boolean unique, boolean localFresh, boolean moved,
               Object uniqueOwner, BitSet elementParams, boolean elementThis, boolean elementExternal,
               Set<Object> directKeys, PathKey pathKey) {
            this.params = params;
            this.aliasesThis = aliasesThis;
            this.external = external;
            this.unique = unique;
            this.localFresh = localFresh;
            this.moved = moved;
            this.uniqueOwner = uniqueOwner;
            this.elementParams = elementParams;
            this.elementThis = elementThis;
            this.elementExternal = elementExternal;
            this.directKeys = directKeys;
            this.pathKey = pathKey;
        }

        Origin(BitSet params, boolean aliasesThis, boolean external, boolean unique, boolean moved,
               Object uniqueOwner, Set<Object> directKeys, PathKey pathKey) {
            this(params, aliasesThis, external, unique, unique && params.isEmpty() && !aliasesThis && !external, moved,
                    uniqueOwner, EMPTY_BITS, false, false, directKeys, pathKey);
        }

        Origin(BitSet params, boolean aliasesThis, boolean external, Set<Object> directKeys, PathKey pathKey) {
            this(params, aliasesThis, external, false, false, false, null, EMPTY_BITS, false, false, directKeys, pathKey);
        }

        static Origin ofParam(int index, Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            BitSet bits = new BitSet();
            bits.set(index);
            return new Origin(bits, false, false, false, false, false, null,
                    EMPTY_BITS, false, false, Set.of(key), new PathKey(key, List.of()));
        }

        static Origin ofUniqueParam(int index, Object key, boolean immutable, boolean borrowed) {
            if (immutable) {
                return IMMUTABLE;
            }
            BitSet bits = new BitSet();
            if (borrowed) {
                bits.set(index);
            }
            return new Origin(bits, false, false, true, !borrowed, false, key,
                    EMPTY_BITS, false, false, Set.of(key), new PathKey(key, List.of()));
        }

        static Origin ofUnique(Object key, Object uniqueOwner, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            Set<Object> keys = key != null ? Set.of(key) : Set.of();
            PathKey pk = key != null ? new PathKey(key, List.of()) : null;
            return new Origin(new BitSet(0), false, false, true, true, false, uniqueOwner,
                    EMPTY_BITS, false, false, keys, pk);
        }

        static Origin ofThis(Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            return new Origin(new BitSet(0), true, false, false, false, false, null,
                    EMPTY_BITS, false, false, Set.of(key), new PathKey(key, List.of()));
        }

        static Origin ofExternal(Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            Set<Object> keys = key != null ? Set.of(key) : Set.of();
            PathKey pk = key != null ? new PathKey(key, List.of()) : null;
            return new Origin(new BitSet(0), false, true, false, false, false, null,
                    EMPTY_BITS, false, false, keys, pk);
        }

        boolean isEmpty() {
            return params.isEmpty() && !aliasesThis && !external && !unique && !localFresh && !moved
                    && elementParams.isEmpty() && !elementThis && !elementExternal;
        }

        boolean hasElementTaint() {
            return !elementParams.isEmpty() || elementThis || elementExternal;
        }

        /** Flattens element provenance into direct provenance (for reads, deep mutations, or escapes). */
        Origin flattenElements() {
            if (!hasElementTaint()) {
                return this;
            }
            BitSet merged = (BitSet) params.clone();
            merged.or(elementParams);
            return new Origin(merged, aliasesThis || elementThis, external || elementExternal,
                    false, false, moved, null, EMPTY_BITS, false, false, directKeys, pathKey);
        }

        Origin deriveMember(String field, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            PathKey nextPath = pathKey != null ? pathKey.append(field) : null;
            if (hasElementTaint()) {
                BitSet merged = (BitSet) params.clone();
                merged.or(elementParams);
                return new Origin(merged, aliasesThis || elementThis, external || elementExternal,
                        false, false, false, null, EMPTY_BITS, false, false, Set.of(), nextPath);
            }
            return new Origin((BitSet) params.clone(), aliasesThis, external, unique, false, false,
                    unique ? uniqueOwner : null, EMPTY_BITS, false, false, Set.of(), nextPath);
        }

        Origin stripDirectIdentity(boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            if (hasElementTaint()) {
                BitSet merged = (BitSet) params.clone();
                merged.or(elementParams);
                return new Origin(merged, aliasesThis || elementThis, external || elementExternal,
                        false, false, false, null, EMPTY_BITS, false, false, Set.of(), null);
            }
            return new Origin((BitSet) params.clone(), aliasesThis, external, unique, false, false,
                    unique ? uniqueOwner : null, EMPTY_BITS, false, false, Set.of(), null);
        }

        /** Stores a non-unique element into a fresh local container: drops {@code unique} while keeping {@code localFresh} (BIP016-D9). */
        Origin absorbElement(Origin taint) {
            BitSet nextElemParams = (BitSet) elementParams.clone();
            boolean nextElemThis = elementThis;
            boolean nextElemExt = elementExternal;
            if (taint != null && taint != IMMUTABLE) {
                nextElemParams.or(taint.params);
                nextElemParams.or(taint.elementParams);
                nextElemThis |= taint.aliasesThis || taint.elementThis;
                nextElemExt |= taint.external || taint.elementExternal;
            }
            return new Origin((BitSet) params.clone(), aliasesThis, external, false, true, false, uniqueOwner,
                    nextElemParams, nextElemThis, nextElemExt, directKeys, pathKey);
        }

        Origin withoutUnique() {
            BitSet merged = (BitSet) params.clone();
            merged.or(elementParams);
            boolean mergedThis = aliasesThis || elementThis;
            boolean mergedExt = external || elementExternal;
            return new Origin(merged, mergedThis, mergedExt, false, false, false, null,
                    EMPTY_BITS, false, false, directKeys, pathKey);
        }

        Origin demoteAlias(Origin taint) {
            BitSet merged = (BitSet) params.clone();
            merged.or(elementParams);
            boolean mergedThis = aliasesThis || elementThis;
            boolean mergedExt = external || elementExternal;
            if (taint != null && taint != IMMUTABLE) {
                merged.or(taint.params);
                merged.or(taint.elementParams);
                mergedThis |= taint.aliasesThis || taint.elementThis;
                mergedExt |= taint.external || taint.elementExternal;
            }
            return new Origin(merged, mergedThis, mergedExt, false, false, false, null,
                    EMPTY_BITS, false, false, directKeys, pathKey);
        }

        Origin demoteToExternal(Origin taint) {
            BitSet merged = (BitSet) params.clone();
            merged.or(elementParams);
            boolean mergedThis = aliasesThis || elementThis;
            if (taint != null && taint != IMMUTABLE) {
                merged.or(taint.params);
                merged.or(taint.elementParams);
                mergedThis |= taint.aliasesThis || taint.elementThis;
            }
            return new Origin(merged, mergedThis, true, false, false, false, null,
                    EMPTY_BITS, false, false, directKeys, pathKey);
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
            BitSet mergedElem = (BitSet) elementParams.clone();
            mergedElem.or(other.elementParams);
            boolean mergedElemThis = elementThis || other.elementThis;
            boolean mergedElemExt = elementExternal || other.elementExternal;
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
            boolean mergedUnique = this.unique && other.unique && !this.external && !other.external
                    && !mergedThis && merged.isEmpty();
            boolean mergedLocalFresh = this.localFresh && other.localFresh && !this.external && !other.external
                    && !mergedThis && merged.isEmpty();
            Object mergedOwner = (mergedUnique || mergedLocalFresh) && Objects.equals(this.uniqueOwner, other.uniqueOwner)
                    ? this.uniqueOwner : null;
            boolean mergedExternal = this.external || other.external
                    || (!mergedUnique && !mergedLocalFresh && (this.unique || other.unique || this.localFresh || other.localFresh)
                        && !mergedThis && merged.isEmpty());
            if (!mergedLocalFresh && (!mergedElem.isEmpty() || mergedElemThis || mergedElemExt)) {
                merged.or(mergedElem);
                mergedThis |= mergedElemThis;
                mergedExternal |= mergedElemExt;
                return new Origin(merged, mergedThis, mergedExternal, false, false, false, null,
                        EMPTY_BITS, false, false, keys, pk);
            }
            return new Origin(merged, mergedThis, mergedExternal, mergedUnique, mergedLocalFresh, false, mergedOwner,
                    mergedElem, mergedElemThis, mergedElemExt, keys, pk);
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
                    joined.unique, joined.localFresh, joined.moved, joined.uniqueOwner,
                    (BitSet) joined.elementParams.clone(), joined.elementThis, joined.elementExternal,
                    joined.directKeys, widenedPath);
        }

        private Origin withoutExactSingleKey() {
            return new Origin((BitSet) params.clone(), aliasesThis, external, unique, localFresh, moved, uniqueOwner,
                    (BitSet) elementParams.clone(), elementThis, elementExternal, directKeys, pathKey);
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
                    && localFresh == that.localFresh
                    && moved == that.moved
                    && elementThis == that.elementThis
                    && elementExternal == that.elementExternal
                    && Objects.equals(uniqueOwner, that.uniqueOwner)
                    && params.equals(that.params)
                    && elementParams.equals(that.elementParams)
                    && directKeys.equals(that.directKeys)
                    && Objects.equals(pathKey, that.pathKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(params, aliasesThis, external, unique, localFresh, moved, uniqueOwner,
                    elementParams, elementThis, elementExternal, directKeys, pathKey);
        }
    }

    /** Depth bound {@code k = 3} for access-path widening ({@code BIP027-P4}, §3.1). */
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
