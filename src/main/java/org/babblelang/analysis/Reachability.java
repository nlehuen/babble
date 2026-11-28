package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ANTLR-free reachability, alias provenance, and path-identity domain for static {@code mut} /
 * {@code borrow} / {@code unique} / exclusivity checking ({@code BIP027-I3}).
 */
final class Reachability {
    private Reachability() {}

    static final Object THIS_KEY = new Object();

    /** Wildcard field key used when a stored object may inhabit any field of a container. */
    static final String ANY_FIELD = "*";

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
            return new Provenance(
                    merged, aliasesThis || other.aliasesThis, external || other.external);
        }
    }

    /**
     * Tracks alias provenance and direct binding identity for static mut/borrow/exclusivity checks.
     */
    static final class Origin {
        static final BitSet EMPTY_BITS = new BitSet(0);
        static final Origin IMMUTABLE =
                new Origin(
                        EMPTY_BITS,
                        false,
                        false,
                        false,
                        null,
                        false,
                        null,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        java.util.Map.of());
        static final Origin MOVED =
                new Origin(
                        EMPTY_BITS,
                        false,
                        false,
                        false,
                        null,
                        true,
                        null,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        java.util.Map.of());

        final BitSet params;
        final boolean aliasesThis;
        final boolean external;
        final boolean unique;

        /**
         * The shallow modality (BIP016-D9, {@code BIP027-I5}) : non-null for a fresh local
         * allocation whose own shell has not escaped (even after storing non-unique elements),
         * holding what its elements reach — propagated on reads, deep mutations, or escapes, never
         * to the shell itself.
         */
        final Provenance shallow;

        final boolean moved;

        /**
         * Owning local/param declaration when {@link #owned()} and bound to a live variable, or
         * {@code null} for a fresh rvalue.
         */
        final Object uniqueOwner;

        /**
         * Direct local/param/this identity keys when an expression is a direct alias (e.g. {@code
         * def y = x}).
         */
        final Set<Object> directKeys;

        /** Access-path set (multi-path across branches and projections, {@code BIP032}). */
        final Set<PathKey> paths;

        /**
         * Truncated paths of elements stored into this container (propagated on collection/array
         * projections, kept separate from named class/record field derivations).
         */
        final Set<PathKey> elementPaths;

        /**
         * Per-field paths of non-unique values stored into named fields of this object (or {@link
         * #ANY_FIELD} when the target field is statically unknown).
         */
        final java.util.Map<String, Set<PathKey>> fieldPaths;

        Origin(
                BitSet params,
                boolean aliasesThis,
                boolean external,
                boolean unique,
                Provenance shallow,
                boolean moved,
                Object uniqueOwner,
                Set<Object> directKeys,
                Set<PathKey> paths,
                Set<PathKey> elementPaths,
                java.util.Map<String, Set<PathKey>> fieldPaths) {
            this.params = params;
            this.aliasesThis = aliasesThis;
            this.external = external;
            this.unique = unique;
            this.shallow = shallow;
            this.moved = moved;
            this.uniqueOwner = uniqueOwner;
            this.directKeys = directKeys;
            this.paths = paths;
            this.elementPaths = elementPaths;
            this.fieldPaths = fieldPaths;
        }

        Origin(
                BitSet params,
                boolean aliasesThis,
                boolean external,
                boolean unique,
                Provenance shallow,
                boolean moved,
                Object uniqueOwner,
                Set<Object> directKeys,
                Set<PathKey> paths,
                Set<PathKey> elementPaths) {
            this(
                    params,
                    aliasesThis,
                    external,
                    unique,
                    shallow,
                    moved,
                    uniqueOwner,
                    directKeys,
                    paths,
                    elementPaths,
                    java.util.Map.of());
        }

        Origin(
                BitSet params,
                boolean aliasesThis,
                boolean external,
                boolean unique,
                Provenance shallow,
                boolean moved,
                Object uniqueOwner,
                Set<Object> directKeys,
                PathKey pathKey) {
            this(
                    params,
                    aliasesThis,
                    external,
                    unique,
                    shallow,
                    moved,
                    uniqueOwner,
                    directKeys,
                    pathKey != null ? Set.of(pathKey) : Set.of(),
                    Set.of(),
                    java.util.Map.of());
        }

        Origin(
                BitSet params,
                boolean aliasesThis,
                boolean external,
                Set<Object> directKeys,
                PathKey pathKey) {
            this(params, aliasesThis, external, false, null, false, null, directKeys, pathKey);
        }

        static Origin ofParam(int index, Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            BitSet bits = new BitSet();
            bits.set(index);
            return new Origin(
                    bits,
                    false,
                    false,
                    false,
                    null,
                    false,
                    null,
                    Set.of(key),
                    Set.of(new PathKey(key, List.of())),
                    Set.of());
        }

        static Origin ofUniqueParam(int index, Object key, boolean immutable, boolean borrowed) {
            if (immutable) {
                return IMMUTABLE;
            }
            BitSet bits = new BitSet();
            if (borrowed) {
                bits.set(index);
            }
            return new Origin(
                    bits,
                    false,
                    false,
                    true,
                    borrowed ? null : Provenance.EMPTY,
                    false,
                    key,
                    Set.of(key),
                    Set.of(new PathKey(key, List.of())),
                    Set.of());
        }

        static Origin ofUnique(Object key, Object uniqueOwner, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            Set<Object> keys = key != null ? Set.of(key) : Set.of();
            Set<PathKey> pks = key != null ? Set.of(new PathKey(key, List.of())) : Set.of();
            return new Origin(
                    new BitSet(0),
                    false,
                    false,
                    true,
                    Provenance.EMPTY,
                    false,
                    uniqueOwner,
                    keys,
                    pks,
                    Set.of());
        }

        static Origin ofThis(Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            return new Origin(
                    new BitSet(0),
                    true,
                    false,
                    false,
                    null,
                    false,
                    null,
                    Set.of(key),
                    Set.of(new PathKey(key, List.of())),
                    Set.of());
        }

        static Origin ofExternal(Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            Set<Object> keys = key != null ? Set.of(key) : Set.of();
            Set<PathKey> pks = key != null ? Set.of(new PathKey(key, List.of())) : Set.of();
            return new Origin(
                    new BitSet(0), false, true, false, null, false, null, keys, pks, Set.of());
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
            return params.isEmpty()
                    && !aliasesThis
                    && !external
                    && !unique
                    && shallow == null
                    && !moved;
        }

        boolean hasElementTaint() {
            return shallow != null && !shallow.isEmpty();
        }

        /** Direct provenance joined with the elements' (for reads, deep mutations, or escapes). */
        private Provenance deep(Origin taint) {
            Provenance p = new Provenance(params, aliasesThis, external).union(elements());
            if (taint != null && taint != IMMUTABLE) {
                p =
                        p.union(new Provenance(taint.params, taint.aliasesThis, taint.external))
                                .union(taint.elements());
            }
            return p;
        }

        /**
         * Flattens element provenance into direct provenance (for reads, deep mutations, or
         * escapes).
         */
        Origin flattenElements() {
            if (!hasElementTaint()) {
                return this;
            }
            Provenance flat = deep(null);
            return new Origin(
                    (BitSet) flat.params().clone(),
                    flat.aliasesThis(),
                    flat.external(),
                    false,
                    null,
                    moved,
                    null,
                    directKeys,
                    paths,
                    elementPaths,
                    fieldPaths);
        }

        Origin deriveMember(String field, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            Set<PathKey> nextPaths = appendPaths(paths, field);
            Set<PathKey> stored = fieldPaths.get(field);
            if (stored != null) {
                nextPaths = unionPaths(nextPaths, stored);
            }
            Set<PathKey> anyStored = fieldPaths.get(ANY_FIELD);
            if (anyStored != null) {
                nextPaths = unionPaths(nextPaths, anyStored);
            }
            return derived(Set.of(), nextPaths, Set.of());
        }

        Origin deriveProjection(boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            Set<PathKey> nextPaths = unionPaths(truncatePaths(paths, directKeys), elementPaths);
            return projectedOwnership(derived(Set.of(), nextPaths, Set.of()));
        }

        /**
         * A projection of a container that absorbed other values as elements (an {@code
         * Optional.of(a)}, a {@code List.of(a)}) may be one of them, still reachable from where it
         * came from : it is not unique, though the container is.
         */
        private Origin projectedOwnership(Origin projected) {
            return elementPaths.isEmpty() || !projected.unique
                    ? projected
                    : projected.withoutUnique();
        }

        Origin deriveIndexedProjection(String indexSeg, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            if (indexSeg == null) {
                return deriveProjection(false);
            }
            Set<PathKey> base = paths.isEmpty() ? rootPathsFromKeys(directKeys, false) : paths;
            Set<PathKey> nextPaths = unionPaths(appendPaths(base, indexSeg), elementPaths);
            return projectedOwnership(derived(Set.of(), nextPaths, Set.of()));
        }

        Origin stripDirectIdentity(boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            return derived(Set.of(), Set.of(), Set.of());
        }

        private Origin derived(
                Set<Object> keys, Set<PathKey> nextPaths, Set<PathKey> nextElemPaths) {
            if (hasElementTaint()) {
                Provenance flat = deep(null);
                return new Origin(
                        (BitSet) flat.params().clone(),
                        flat.aliasesThis(),
                        flat.external(),
                        false,
                        null,
                        false,
                        null,
                        keys,
                        nextPaths,
                        nextElemPaths,
                        java.util.Map.of());
            }
            return new Origin(
                    (BitSet) params.clone(),
                    aliasesThis,
                    external,
                    unique,
                    null,
                    false,
                    unique ? uniqueOwner : null,
                    keys,
                    nextPaths,
                    nextElemPaths,
                    java.util.Map.of());
        }

        /**
         * Drops {@code unique} while preserving the shallow local-fresh shell (used when a
         * frame-local closure captures a fresh local container, {@code BIP032}).
         */
        Origin dropUniqueKeepFresh() {
            if (!unique) {
                return this;
            }
            return new Origin(
                    (BitSet) params.clone(),
                    aliasesThis,
                    external,
                    false,
                    cloned(shallow),
                    moved,
                    shallow != null ? uniqueOwner : null,
                    directKeys,
                    paths,
                    elementPaths,
                    fieldPaths);
        }

        /**
         * Stores a non-unique element into a fresh local container: drops {@code unique} while
         * keeping the shell fresh (BIP016-D9).
         */
        Origin absorbElement(Origin taint) {
            Provenance elems = elements();
            Set<PathKey> nextElemPaths = elementPaths;
            if (taint != null && taint != IMMUTABLE) {
                elems =
                        elems.union(new Provenance(taint.params, taint.aliasesThis, taint.external))
                                .union(taint.elements());
                Set<PathKey> taintPaths =
                        unionPaths(
                                truncatePaths(taint.paths, taint.directKeys),
                                unionPaths(taint.elementPaths, taint.allFieldPaths()));
                nextElemPaths = unionPaths(nextElemPaths, taintPaths);
            }
            return new Origin(
                    (BitSet) params.clone(),
                    aliasesThis,
                    external,
                    false,
                    cloned(elems),
                    false,
                    uniqueOwner,
                    directKeys,
                    paths,
                    nextElemPaths,
                    fieldPaths);
        }

        /**
         * Stores a non-unique value into named field {@code fieldName} (or {@link #ANY_FIELD}) of a
         * fresh local object, updating both element provenance and per-field alias paths.
         */
        Origin absorbField(String fieldName, Origin taint) {
            Origin absorbed = absorbElement(taint);
            if (taint == null || taint == IMMUTABLE) {
                return absorbed;
            }
            Set<PathKey> storedPaths = taint.exactAndInnerPaths();
            if (storedPaths.isEmpty()) {
                return absorbed;
            }
            String key = fieldName != null ? fieldName : ANY_FIELD;
            java.util.Map<String, Set<PathKey>> nextFields =
                    putFieldPaths(absorbed.fieldPaths, key, storedPaths);
            return new Origin(
                    (BitSet) absorbed.params.clone(),
                    absorbed.aliasesThis,
                    absorbed.external,
                    absorbed.unique,
                    cloned(absorbed.shallow),
                    absorbed.moved,
                    absorbed.uniqueOwner,
                    absorbed.directKeys,
                    absorbed.paths,
                    absorbed.elementPaths,
                    nextFields);
        }

        /** Records per-field stored alias paths without altering ownership/freshness flags. */
        Origin withFieldPaths(String fieldName, Origin value) {
            if (this == IMMUTABLE || this == MOVED || value == null || value == IMMUTABLE) {
                return this;
            }
            Set<PathKey> storedPaths = value.exactAndInnerPaths();
            if (storedPaths.isEmpty()) {
                return this;
            }
            String key = fieldName != null ? fieldName : ANY_FIELD;
            java.util.Map<String, Set<PathKey>> nextFields =
                    putFieldPaths(fieldPaths, key, storedPaths);
            return new Origin(
                    (BitSet) params.clone(),
                    aliasesThis,
                    external,
                    unique,
                    cloned(shallow),
                    moved,
                    uniqueOwner,
                    directKeys,
                    paths,
                    elementPaths,
                    nextFields);
        }

        /** Attaches additional alias paths (e.g. closure capture paths for exclusivity checks). */
        Origin withAddedPaths(Set<PathKey> extraPaths) {
            if (extraPaths == null || extraPaths.isEmpty() || this == MOVED) {
                return this;
            }
            Set<PathKey> nextPaths = unionPaths(paths, extraPaths);
            return new Origin(
                    (BitSet) params.clone(),
                    aliasesThis,
                    external,
                    unique,
                    cloned(shallow),
                    moved,
                    uniqueOwner,
                    directKeys,
                    nextPaths,
                    elementPaths,
                    fieldPaths);
        }

        Set<PathKey> exactAndInnerPaths() {
            Set<PathKey> base = paths.isEmpty() ? rootPathsFromKeys(directKeys, false) : paths;
            return unionPaths(base, unionPaths(elementPaths, allFieldPaths()));
        }

        Set<PathKey> allReachablePaths() {
            return unionPaths(
                    truncatePaths(paths, directKeys), unionPaths(elementPaths, allFieldPaths()));
        }

        Set<PathKey> allFieldPaths() {
            if (fieldPaths.isEmpty()) {
                return Set.of();
            }
            Set<PathKey> out = new HashSet<>();
            for (Set<PathKey> s : fieldPaths.values()) {
                out.addAll(s);
            }
            return Set.copyOf(out);
        }

        Origin withoutUnique() {
            return demoteAlias(null);
        }

        Origin demoteAlias(Origin taint) {
            Provenance flat = deep(taint);
            return new Origin(
                    (BitSet) flat.params().clone(),
                    flat.aliasesThis(),
                    flat.external(),
                    false,
                    null,
                    false,
                    null,
                    directKeys,
                    paths,
                    elementPaths,
                    fieldPaths);
        }

        Origin demoteToExternal(Origin taint) {
            Provenance flat = deep(taint);
            return new Origin(
                    (BitSet) flat.params().clone(),
                    flat.aliasesThis(),
                    true,
                    false,
                    null,
                    false,
                    null,
                    directKeys,
                    paths,
                    elementPaths,
                    fieldPaths);
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
            Set<PathKey> mergedPaths = unionPaths(this.paths, other.paths);
            Set<PathKey> mergedElemPaths = unionPaths(this.elementPaths, other.elementPaths);
            java.util.Map<String, Set<PathKey>> mergedFieldPaths =
                    unionFieldPaths(this.fieldPaths, other.fieldPaths);
            boolean mergedThis = aliasesThis || other.aliasesThis;
            boolean confined = !this.external && !other.external && !mergedThis && merged.isEmpty();
            boolean mergedUnique = this.unique && other.unique && confined;
            boolean mergedLocalFresh = this.localFresh() && other.localFresh() && confined;
            Object mergedOwner =
                    (mergedUnique || mergedLocalFresh)
                                    && Objects.equals(this.uniqueOwner, other.uniqueOwner)
                            ? this.uniqueOwner
                            : null;
            boolean mergedExternal =
                    this.external
                            || other.external
                            || (!mergedUnique
                                    && !mergedLocalFresh
                                    && (this.owned() || other.owned())
                                    && confined);
            if (!mergedLocalFresh && !mergedElems.isEmpty()) {
                merged.or(mergedElems.params());
                return new Origin(
                        merged,
                        mergedThis || mergedElems.aliasesThis(),
                        mergedExternal || mergedElems.external(),
                        false,
                        null,
                        false,
                        null,
                        keys,
                        mergedPaths,
                        mergedElemPaths,
                        mergedFieldPaths);
            }
            return new Origin(
                    merged,
                    mergedThis,
                    mergedExternal,
                    mergedUnique,
                    mergedLocalFresh ? cloned(mergedElems) : null,
                    false,
                    mergedOwner,
                    keys,
                    mergedPaths,
                    mergedElemPaths,
                    mergedFieldPaths);
        }

        Origin widen(Origin other) {
            Origin joined = this.join(other);
            if (joined == MOVED || joined == IMMUTABLE) {
                return joined;
            }
            Set<PathKey> widenedPaths = widenPaths(this.paths, other.paths);
            Set<PathKey> widenedElemPaths = widenPaths(this.elementPaths, other.elementPaths);
            java.util.Map<String, Set<PathKey>> widenedFieldPaths =
                    widenFieldPaths(this.fieldPaths, other.fieldPaths);
            if (widenedPaths.equals(joined.paths)
                    && widenedElemPaths.equals(joined.elementPaths)
                    && widenedFieldPaths.equals(joined.fieldPaths)) {
                return joined;
            }
            return new Origin(
                    (BitSet) joined.params.clone(),
                    joined.aliasesThis,
                    joined.external,
                    joined.unique,
                    cloned(joined.shallow),
                    joined.moved,
                    joined.uniqueOwner,
                    joined.directKeys,
                    widenedPaths,
                    widenedElemPaths,
                    widenedFieldPaths);
        }

        private Origin withoutExactSingleKey() {
            return new Origin(
                    (BitSet) params.clone(),
                    aliasesThis,
                    external,
                    unique,
                    cloned(shallow),
                    moved,
                    uniqueOwner,
                    directKeys,
                    paths,
                    elementPaths,
                    fieldPaths);
        }

        private static Provenance cloned(Provenance p) {
            return p == null || p.params().isEmpty()
                    ? p
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
                    && paths.equals(that.paths)
                    && elementPaths.equals(that.elementPaths)
                    && fieldPaths.equals(that.fieldPaths);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    params,
                    aliasesThis,
                    external,
                    unique,
                    shallow,
                    moved,
                    uniqueOwner,
                    directKeys,
                    paths,
                    elementPaths,
                    fieldPaths);
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

        PathKey truncate() {
            return truncated ? this : new PathKey(root, segments, true);
        }

        /**
         * Widens two access paths to their longest common prefix (bounded height <=
         * MAX_PATH_DEPTH).
         */
        PathKey widen(PathKey other) {
            if (other == null || !Objects.equals(root, other.root)) {
                return null;
            }
            int limit = Math.min(segments.size(), other.segments.size());
            int i = 0;
            while (i < limit && segments.get(i).equals(other.segments.get(i))) {
                i++;
            }
            if (i == segments.size()
                    && i == other.segments.size()
                    && truncated == other.truncated) {
                return this;
            }
            return new PathKey(root, List.copyOf(segments.subList(0, i)), true);
        }

        /**
         * Prefix-aware path overlap ({@code BIP027-D5}): {@code pair} overlaps {@code pair.a} and
         * {@code pair.a.x} overlaps {@code pair.a}, whereas disjoint sibling fields ({@code pair.a}
         * vs {@code pair.b}) do not overlap.
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

    private static Set<PathKey> appendPaths(Set<PathKey> paths, String seg) {
        if (paths.isEmpty()) {
            return Set.of();
        }
        Set<PathKey> out = new HashSet<>(paths.size());
        for (PathKey p : paths) {
            out.add(p.append(seg));
        }
        return Set.copyOf(out);
    }

    private static Set<PathKey> rootPathsFromKeys(Set<Object> keys, boolean truncated) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        Set<PathKey> out = new HashSet<>(keys.size());
        for (Object k : keys) {
            out.add(new PathKey(k, List.of(), truncated));
        }
        return Set.copyOf(out);
    }

    static Set<PathKey> truncatePaths(Set<PathKey> paths, Set<Object> fallbackKeys) {
        if (paths.isEmpty()) {
            return rootPathsFromKeys(fallbackKeys, true);
        }
        Set<PathKey> out = new HashSet<>(paths.size());
        for (PathKey p : paths) {
            out.add(p.truncate());
        }
        return Set.copyOf(out);
    }

    static Set<PathKey> unionPaths(Set<PathKey> a, Set<PathKey> b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        Set<PathKey> out = new HashSet<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return Set.copyOf(out);
    }

    private static java.util.Map<String, Set<PathKey>> putFieldPaths(
            java.util.Map<String, Set<PathKey>> existing, String field, Set<PathKey> added) {
        java.util.Map<String, Set<PathKey>> copy = new java.util.HashMap<>(existing);
        copy.merge(field, added, Reachability::unionPaths);
        return java.util.Map.copyOf(copy);
    }

    private static java.util.Map<String, Set<PathKey>> unionFieldPaths(
            java.util.Map<String, Set<PathKey>> a, java.util.Map<String, Set<PathKey>> b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        java.util.Map<String, Set<PathKey>> out = new java.util.HashMap<>(a);
        for (java.util.Map.Entry<String, Set<PathKey>> e : b.entrySet()) {
            out.merge(e.getKey(), e.getValue(), Reachability::unionPaths);
        }
        return java.util.Map.copyOf(out);
    }

    private static java.util.Map<String, Set<PathKey>> widenFieldPaths(
            java.util.Map<String, Set<PathKey>> a, java.util.Map<String, Set<PathKey>> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, Set<PathKey>> out = new java.util.HashMap<>(a);
        for (java.util.Map.Entry<String, Set<PathKey>> e : b.entrySet()) {
            out.merge(e.getKey(), e.getValue(), Reachability::widenPaths);
        }
        return java.util.Map.copyOf(out);
    }

    private static Set<PathKey> widenPaths(Set<PathKey> a, Set<PathKey> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return Set.of();
        }
        java.util.Map<Object, PathKey> byRoot = new java.util.HashMap<>();
        for (PathKey p : a) {
            byRoot.merge(p.root(), p, PathKey::widen);
        }
        for (PathKey p : b) {
            byRoot.merge(p.root(), p, PathKey::widen);
        }
        return Set.copyOf(byRoot.values());
    }

    static boolean staticallySameObject(Origin a, Origin b) {
        return staticallyAliases(a, false, b, false);
    }

    static boolean staticallyAliases(Origin a, boolean aShallow, Origin b, boolean bShallow) {
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
        Set<PathKey> aPaths = aShallow ? a.paths : allReachablePaths(a);
        Set<PathKey> bPaths = bShallow ? b.paths : allReachablePaths(b);
        if (!aPaths.isEmpty() && !bPaths.isEmpty()) {
            for (PathKey pa : aPaths) {
                for (PathKey pb : bPaths) {
                    if (pa.overlaps(pb)) {
                        return true;
                    }
                }
            }
        }
        if (!aPaths.isEmpty() && !b.directKeys.isEmpty()) {
            for (PathKey pa : aPaths) {
                if (b.directKeys.contains(pa.root())) {
                    return true;
                }
            }
        }
        if (!bPaths.isEmpty() && !a.directKeys.isEmpty()) {
            for (PathKey pb : bPaths) {
                if (a.directKeys.contains(pb.root())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<PathKey> allReachablePaths(Origin o) {
        if (o.elementPaths.isEmpty() && o.fieldPaths.isEmpty()) {
            return o.paths;
        }
        Set<PathKey> all = new HashSet<>(o.paths);
        all.addAll(o.elementPaths);
        for (Set<PathKey> fp : o.fieldPaths.values()) {
            all.addAll(fp);
        }
        return all;
    }
}
