package org.babblelang.analysis;

import org.babblelang.runtime.ClassShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AST-detached Maranget-style pattern-match exhaustiveness and reachability analyzer
 * ({@code BIP038-D3}, {@code BIP038-D8}).
 *
 * <p>Decomposes closed domains ({@link Type.Union}, {@code T?}, {@link Type#BOOL},
 * {@link Type#NULL}, Java {@code enum}s, Java {@code sealed} classes/interfaces, Babble
 * and Java {@code record}s, and {@code list}/{@code array} sequence lengths), detects
 * unreachable arms including open-domain constant/range shadowing, produces minimal
 * counterexample witnesses, and exposes {@link #permittedSubtypes(Type)} as the extension
 * hook for future Babble {@code sealed}/{@code enum} declarations.
 */
final class MatchExhaustiveness {

    /**
     * Sentinel witness indicating an open (infinite) domain is uncovered without a single
     * concrete value constructor. At top level ({@code depth == 0}) this renders as
     * {@code null} witness ("add an else branch"); inside compound patterns it renders as {@code "_"}.
     */
    private static final String OPEN_SENTINEL = "";

    sealed interface Pat permits
            Pat.Wildcard,
            Pat.Or,
            Pat.BoolConst,
            Pat.NullConst,
            Pat.EnumConst,
            Pat.ScalarConst,
            Pat.ConstRange,
            Pat.TypePat,
            Pat.RecordPat,
            Pat.SeqPat,
            Pat.Opaque {

        record Wildcard() implements Pat {
            static final Wildcard INSTANCE = new Wildcard();
        }

        record Or(List<Pat> alts) implements Pat {
        }

        record BoolConst(boolean value) implements Pat {
            static final BoolConst TRUE = new BoolConst(true);
            static final BoolConst FALSE = new BoolConst(false);
        }

        record NullConst() implements Pat {
            static final NullConst INSTANCE = new NullConst();
        }

        record EnumConst(Class<?> enumClass, String name) implements Pat {
        }

        record ScalarConst(Object value, PrimitiveKind kind) implements Pat {
        }

        record ConstRange(long lo, long hi, boolean inclusive, long step,
                          boolean isChar, boolean isDouble, double dLo, double dHi) implements Pat {
        }

        record TypePat(Type target) implements Pat {
        }

        record RecordPat(Type recordType, boolean isJavaRecord, TypeNode babbleNode,
                         Class<?> javaRecordClass, List<Pat> fields) implements Pat {
        }

        record SeqPat(boolean isArray, Type targetType, List<Pat> elems, boolean hasRest) implements Pat {
        }

        /** Runtime expression, relational comparison, {@code in} membership, or non-constant range. */
        record Opaque() implements Pat {
            static final Opaque INSTANCE = new Opaque();
        }
    }

    record Arm(Pat pattern, boolean guarded) {
    }

    record Result(boolean exhaustive, String missingWitness, List<Integer> unreachableArms, boolean redundantElse) {
    }

    interface FieldResolver {
        /** Returns canonical constructor-order field types for a Babble or Java record type. */
        List<Type> recordFieldTypes(Type recordType);
    }

    private final TypeAlgebra algebra;
    private final FieldResolver fieldResolver;

    private MatchExhaustiveness(TypeAlgebra algebra, FieldResolver fieldResolver) {
        this.algebra = algebra;
        this.fieldResolver = fieldResolver;
    }

    static Result check(Type scrutineeType, List<Arm> arms, boolean hasElse,
                        TypeAlgebra algebra, FieldResolver fieldResolver) {
        if (scrutineeType == null || scrutineeType == Type.ERROR || scrutineeType == Type.NOTHING) {
            return new Result(true, null, List.of(), false);
        }
        MatchExhaustiveness engine = new MatchExhaustiveness(algebra, fieldResolver);
        List<List<Pat>> matrix = new ArrayList<>();
        List<Integer> unreachable = new ArrayList<>();
        List<Type> rootTypes = List.of(scrutineeType);

        for (int i = 0; i < arms.size(); i++) {
            Arm arm = arms.get(i);
            if (!engine.isUseful(matrix, List.of(arm.pattern()), rootTypes)) {
                unreachable.add(i);
            } else if (!arm.guarded()) {
                matrix.add(List.of(arm.pattern()));
            }
        }

        List<String> witness = engine.findMissingWitness(matrix, rootTypes, 0);
        boolean exhaustive = witness == null;
        String missingWitness = null;
        if (!exhaustive && !witness.isEmpty()) {
            String top = witness.get(0);
            missingWitness = OPEN_SENTINEL.equals(top) ? null : top;
        }
        return new Result(exhaustive, missingWitness, List.copyOf(unreachable), exhaustive && hasElse);
    }

    /**
     * Extension hook ({@code BIP038-D3}): returns the closed direct subtypes of {@code type} when
     * {@code type} is a statically closed sum ({@link Type.Union}, distributed intersection of unions,
     * or Java {@code sealed} class/interface), or an empty list otherwise.
     */
    static List<Type> permittedSubtypes(Type type) {
        if (type == null || type == Type.ERROR || type == Type.NOTHING) {
            return List.of();
        }
        boolean isFrozen = type instanceof Type.Frozen;
        Type base = TypeAlgebra.unwrapFrozen(type);
        if (base instanceof Type.Union || base instanceof Type.Intersection) {
            List<Type> alts = TypeAlgebra.alternatives(base);
            if (alts.size() > 1) {
                if (!isFrozen) {
                    return alts;
                }
                List<Type> out = new ArrayList<>(alts.size());
                for (Type a : alts) {
                    out.add(Type.frozen(a));
                }
                return out;
            }
        }
        if (base instanceof Type.JavaInstance ji && ji.type().isSealed()) {
            Class<?>[] permitted = ji.type().getPermittedSubclasses();
            if (permitted != null && permitted.length > 0) {
                List<Type> out = new ArrayList<>(permitted.length);
                for (Class<?> sub : permitted) {
                    Type subType = new Type.JavaInstance(sub, List.of());
                    out.add(isFrozen ? Type.frozen(subType) : subType);
                }
                return out;
            }
        }
        return List.of();
    }

    /**
     * Recursively expands {@link #permittedSubtypes(Type)} with a cycle guard on sealed classes,
     * returning the flat leaf alternatives (or empty if {@code type} is not a closed sum).
     */
    private static List<Type> flattenClosedSum(Type type) {
        List<Type> direct = permittedSubtypes(type);
        if (direct.isEmpty()) {
            return List.of();
        }
        Set<Class<?>> seenSealed = new HashSet<>();
        Type base = TypeAlgebra.unwrapFrozen(type);
        if (base instanceof Type.JavaInstance ji && ji.type().isSealed()) {
            seenSealed.add(ji.type());
        }
        Set<Type> leaves = new LinkedHashSet<>();
        collectSumLeaves(direct, seenSealed, leaves);
        return new ArrayList<>(leaves);
    }

    private static void collectSumLeaves(List<Type> members, Set<Class<?>> seenSealed, Set<Type> out) {
        for (Type m : members) {
            Type base = TypeAlgebra.unwrapFrozen(m);
            if (base instanceof Type.JavaInstance ji && ji.type().isSealed()) {
                if (!seenSealed.add(ji.type())) {
                    out.add(m);
                    continue;
                }
            }
            List<Type> sub = permittedSubtypes(m);
            if (sub.isEmpty()) {
                out.add(m);
            } else {
                collectSumLeaves(sub, seenSealed, out);
            }
        }
    }

    /** Whether a Java {@code enum} or {@code sealed} type appears in {@code type} (drives runtime fallback check). */
    static boolean containsExternalClosedJavaType(Type type) {
        if (type == null || type == Type.ERROR || type == Type.NOTHING) {
            return false;
        }
        for (Type m : TypeAlgebra.alternatives(TypeAlgebra.unwrapFrozen(type))) {
            Type base = TypeAlgebra.unwrapFrozen(m);
            if (base instanceof Type.JavaInstance ji && (ji.type().isEnum() || ji.type().isSealed())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- usefulness U(P, q)

    private boolean isUseful(List<List<Pat>> matrix, List<Pat> q, List<Type> types) {
        if (types.isEmpty()) {
            return matrix.isEmpty();
        }
        Pat q0 = q.get(0);
        if (q0 instanceof Pat.Or or) {
            List<Pat> restQ = q.subList(1, q.size());
            for (Pat alt : or.alts()) {
                if (isUseful(matrix, prepend(alt, restQ), types)) {
                    return true;
                }
            }
            return false;
        }
        if (matrix.isEmpty()) {
            return true;
        }

        List<List<Pat>> expandedP = expandOrInCol0(matrix);
        Type col0 = types.get(0);
        Type base0 = TypeAlgebra.unwrapFrozen(col0);
        List<Type> restTypes = types.subList(1, types.size());
        List<Pat> restQ = q.subList(1, q.size());

        // 1. Closed sum (Union, T?, Java sealed)
        List<Type> sumLeaves = flattenClosedSum(col0);
        if (!sumLeaves.isEmpty()) {
            for (Type leaf : sumLeaves) {
                Pat qForLeaf = adaptPatForSubtype(q0, leaf);
                if (qForLeaf == null) {
                    continue;
                }
                List<List<Pat>> pForLeaf = filterMatrixForSubtype(expandedP, leaf);
                if (isUseful(pForLeaf, prepend(qForLeaf, restQ), prependType(leaf, restTypes))) {
                    return true;
                }
            }
            return false;
        }

        // 2. Null singleton
        if (base0 == Type.NULL) {
            if (!matchesNull(q0)) {
                return false;
            }
            List<List<Pat>> pNull = specializeNull(expandedP);
            return isUseful(pNull, restQ, restTypes);
        }

        // 3. Boolean {true, false}
        if (base0 == Type.BOOL) {
            if (q0 instanceof Pat.BoolConst bc) {
                return isUseful(specializeBool(expandedP, bc.value()), restQ, restTypes);
            }
            if (q0 instanceof Pat.Wildcard || q0 instanceof Pat.Opaque
                    || (q0 instanceof Pat.TypePat tp && typeCovers(tp.target(), Type.BOOL))) {
                return isUseful(specializeBool(expandedP, true), restQ, restTypes)
                        || isUseful(specializeBool(expandedP, false), restQ, restTypes);
            }
            return false;
        }

        // 4. Java enum
        if (base0 instanceof Type.JavaInstance ji && ji.type().isEnum()) {
            Object[] constants = ji.type().getEnumConstants();
            if (constants == null || constants.length == 0) {
                return false;
            }
            if (q0 instanceof Pat.EnumConst ec) {
                if (ec.enumClass() != ji.type()) {
                    return false;
                }
                return isUseful(specializeEnum(expandedP, ji.type(), ec.name()), restQ, restTypes);
            }
            if (q0 instanceof Pat.Wildcard || q0 instanceof Pat.Opaque
                    || (q0 instanceof Pat.TypePat tp && typeCovers(tp.target(), base0))) {
                for (Object c : constants) {
                    String name = ((Enum<?>) c).name();
                    if (isUseful(specializeEnum(expandedP, ji.type(), name), restQ, restTypes)) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }

        // 5. Babble or Java record
        if (isRecordType(base0) && (q0 instanceof Pat.RecordPat || hasRecordPatInCol0(expandedP, base0))) {
            List<Type> fieldTypes = fieldResolver.recordFieldTypes(col0);
            int arity = fieldTypes.size();
            List<Pat> qFields = specializeRecordPat(q0, base0, arity);
            if (qFields == null) {
                return false;
            }
            List<List<Pat>> pRec = specializeRecordMatrix(expandedP, base0, arity);
            List<Type> nextTypes = concatTypes(fieldTypes, restTypes);
            return isUseful(pRec, concatPats(qFields, restQ), nextTypes);
        }

        // 6. List / array sequence length decomposition
        if (isSequenceType(base0) && (q0 instanceof Pat.SeqPat || hasSeqPatInCol0(expandedP, base0))) {
            boolean isArray = base0 instanceof Type.Array;
            Type elemType = sequenceElementType(col0);
            int splitM = computeSequenceSplitThreshold(expandedP, q0, isArray, base0);
            // Exact lengths 0 .. splitM - 1
            for (int k = 0; k < splitM; k++) {
                List<Pat> qElems = specializeSeqExactPat(q0, isArray, base0, k);
                if (qElems == null) {
                    continue;
                }
                List<List<Pat>> pSeq = specializeSeqExactMatrix(expandedP, isArray, base0, k);
                if (isUseful(pSeq, concatPats(qElems, restQ), prependRepeatedType(elemType, k, restTypes))) {
                    return true;
                }
            }
            // Tail lengths >= splitM
            List<Pat> qTail = specializeSeqAtLeastPat(q0, isArray, base0, splitM);
            if (qTail != null) {
                List<List<Pat>> pTail = specializeSeqAtLeastMatrix(expandedP, isArray, base0, splitM);
                if (isUseful(pTail, concatPats(qTail, restQ), prependRepeatedType(elemType, splitM, restTypes))) {
                    return true;
                }
            }
            return false;
        }

        // 7. Open domain (int, long, double, char, string, open class/interface, any, un-destructured record/seq)
        return isUsefulOpen(expandedP, q0, restQ, col0, base0, restTypes);
    }

    private boolean isUsefulOpen(List<List<Pat>> expandedP, Pat q0, List<Pat> restQ,
                                 Type col0, Type base0, List<Type> restTypes) {
        if (q0 instanceof Pat.Wildcard || q0 instanceof Pat.Opaque
                || (q0 instanceof Pat.TypePat tp && typeCovers(tp.target(), base0))) {
            return isUseful(defaultMatrix(expandedP, base0), restQ, restTypes);
        }
        if (q0 instanceof Pat.TypePat tp) {
            // Narrower type pattern on an open/supertype column (e.g. `is int` or `is bool` on `any` or `Object`):
            Type narrowed = narrowColType(col0, tp.target());
            if (narrowed != null && !narrowed.equals(col0)) {
                List<List<Pat>> pSub = filterMatrixForSubtype(expandedP, narrowed);
                return isUseful(pSub, prepend(Pat.Wildcard.INSTANCE, restQ), prependType(narrowed, restTypes));
            }
            return isUseful(defaultMatrixCovering(expandedP, tp.target()), restQ, restTypes);
        }
        if (q0 instanceof Pat.RecordPat rp) {
            Type narrowed = narrowColType(col0, rp.recordType());
            if (narrowed != null && !narrowed.equals(col0)) {
                List<List<Pat>> pSub = filterMatrixForSubtype(expandedP, narrowed);
                return isUseful(pSub, prepend(rp, restQ), prependType(narrowed, restTypes));
            }
            return isUseful(defaultMatrix(expandedP, base0), restQ, restTypes);
        }
        if (q0 instanceof Pat.SeqPat sp) {
            Type target = sp.targetType() != null ? sp.targetType()
                    : (sp.isArray() ? new Type.Array(Type.NULLABLE_ANY) : new Type.JavaInstance(List.class, List.of()));
            Type narrowed = narrowColType(col0, target);
            if (narrowed != null && !narrowed.equals(col0)) {
                List<List<Pat>> pSub = filterMatrixForSubtype(expandedP, narrowed);
                return isUseful(pSub, prepend(sp, restQ), prependType(narrowed, restTypes));
            }
            return isUseful(defaultMatrix(expandedP, base0), restQ, restTypes);
        }
        if (q0 instanceof Pat.ScalarConst sc) {
            List<List<Pat>> matching = new ArrayList<>();
            for (List<Pat> row : expandedP) {
                if (rowCoversScalar(row.get(0), sc, base0)) {
                    matching.add(row.subList(1, row.size()));
                }
            }
            return isUseful(matching, restQ, restTypes);
        }
        if (q0 instanceof Pat.ConstRange qr) {
            List<List<Pat>> matching = new ArrayList<>();
            for (List<Pat> row : expandedP) {
                if (rowCoversRange(row.get(0), qr, base0)) {
                    matching.add(row.subList(1, row.size()));
                }
            }
            if (!isUseful(matching, restQ, restTypes)) {
                return false;
            }
            // Single-column step-1 discrete interval union check (e.g. `0..5` + `6..10` covers `3..8`)
            if (restQ.isEmpty() && !qr.isDouble() && qr.step() == 1 && isCoveredByDiscreteUnion(expandedP, qr)) {
                return false;
            }
            return true;
        }
        if (q0 instanceof Pat.BoolConst bc) {
            // Matching `true`/`false` on `any`
            List<List<Pat>> pSub = filterMatrixForSubtype(expandedP, Type.BOOL);
            return isUseful(pSub, prepend(bc, restQ), prependType(Type.BOOL, restTypes));
        }
        if (q0 instanceof Pat.NullConst) {
            List<List<Pat>> pSub = filterMatrixForSubtype(expandedP, Type.NULL);
            return isUseful(pSub, prepend(q0, restQ), prependType(Type.NULL, restTypes));
        }
        if (q0 instanceof Pat.EnumConst ec) {
            Type enumType = new Type.JavaInstance(ec.enumClass(), List.of());
            List<List<Pat>> pSub = filterMatrixForSubtype(expandedP, enumType);
            return isUseful(pSub, prepend(ec, restQ), prependType(enumType, restTypes));
        }
        return isUseful(defaultMatrix(expandedP, base0), restQ, restTypes);
    }

    // ---------------------------------------------------------------- counterexample witness search

    /**
     * Returns {@code null} if {@code matrix} covers every value of {@code types}, otherwise a
     * per-column formatted counterexample witness list of size {@code types.size()}.
     */
    private List<String> findMissingWitness(List<List<Pat>> matrix, List<Type> types, int depth) {
        if (types.isEmpty()) {
            return matrix.isEmpty() ? List.of() : null;
        }
        Type col0 = types.get(0);
        Type base0 = TypeAlgebra.unwrapFrozen(col0);
        List<Type> restTypes = types.subList(1, types.size());
        List<List<Pat>> expandedP = expandOrInCol0(matrix);

        // 1. Closed sum (Union, T?, Java sealed)
        List<Type> sumLeaves = flattenClosedSum(col0);
        if (!sumLeaves.isEmpty()) {
            for (Type leaf : sumLeaves) {
                List<List<Pat>> pLeaf = filterMatrixForSubtype(expandedP, leaf);
                List<String> w = findMissingWitness(pLeaf, prependType(leaf, restTypes), depth);
                if (w != null) {
                    String head = w.get(0);
                    if (OPEN_SENTINEL.equals(head) || "_".equals(head)) {
                        head = formatTypeWitness(leaf);
                    }
                    return replaceHead(w, head);
                }
            }
            return null;
        }

        // 2. Null singleton
        if (base0 == Type.NULL) {
            List<String> w = findMissingWitness(specializeNull(expandedP), restTypes, depth);
            return w == null ? null : prependStr("null", w);
        }

        // 3. Boolean {true, false}
        if (base0 == Type.BOOL) {
            List<String> wt = findMissingWitness(specializeBool(expandedP, true), restTypes, depth);
            if (wt != null) {
                return prependStr("true", wt);
            }
            List<String> wf = findMissingWitness(specializeBool(expandedP, false), restTypes, depth);
            if (wf != null) {
                return prependStr("false", wf);
            }
            return null;
        }

        // 4. Java enum
        if (base0 instanceof Type.JavaInstance ji && ji.type().isEnum()) {
            Object[] constants = ji.type().getEnumConstants();
            if (constants == null) {
                return null;
            }
            for (Object c : constants) {
                String name = ((Enum<?>) c).name();
                List<String> w = findMissingWitness(specializeEnum(expandedP, ji.type(), name), restTypes, depth);
                if (w != null) {
                    return prependStr(ji.type().getSimpleName() + "." + name, w);
                }
            }
            return null;
        }

        // 5. Babble or Java record (only decompose into fields if at least one row destructures it)
        if (isRecordType(base0) && hasRecordPatInCol0(expandedP, base0)) {
            List<Type> fieldTypes = fieldResolver.recordFieldTypes(col0);
            int arity = fieldTypes.size();
            List<List<Pat>> pRec = specializeRecordMatrix(expandedP, base0, arity);
            List<String> w = findMissingWitness(pRec, concatTypes(fieldTypes, restTypes), depth + 1);
            if (w == null) {
                return null;
            }
            List<String> fieldWitnesses = new ArrayList<>(arity);
            for (int i = 0; i < arity; i++) {
                String fw = w.get(i);
                fieldWitnesses.add(OPEN_SENTINEL.equals(fw) ? "_" : fw);
            }
            String formatted = recordSimpleName(base0) + "(" + String.join(", ", fieldWitnesses) + ")";
            return prependStr(formatted, w.subList(arity, w.size()));
        }

        // 6. List / array sequence length decomposition (when at least one row destructures it)
        if (isSequenceType(base0) && hasSeqPatInCol0(expandedP, base0)) {
            boolean isArray = base0 instanceof Type.Array;
            Type elemType = sequenceElementType(col0);
            int splitM = computeSequenceSplitThreshold(expandedP, null, isArray, base0);
            String seqName = isArray ? "array" : "list";
            // Shortest uncovered exact length 0 .. splitM - 1
            for (int k = 0; k < splitM; k++) {
                List<List<Pat>> pSeq = specializeSeqExactMatrix(expandedP, isArray, base0, k);
                List<String> w = findMissingWitness(pSeq, prependRepeatedType(elemType, k, restTypes), depth + 1);
                if (w != null) {
                    return prependStr(formatSeqWitness(seqName, w, k), w.subList(k, w.size()));
                }
            }
            // Uncovered tail length >= splitM (witnessed at length splitM)
            List<List<Pat>> pTail = specializeSeqAtLeastMatrix(expandedP, isArray, base0, splitM);
            List<String> w = findMissingWitness(pTail, prependRepeatedType(elemType, splitM, restTypes), depth + 1);
            if (w != null) {
                return prependStr(formatSeqWitness(seqName, w, splitM), w.subList(splitM, w.size()));
            }
            return null;
        }

        // 7. Open domain (or un-destructured record/sequence)
        List<String> w = findMissingWitness(defaultMatrix(expandedP, base0), restTypes, depth);
        if (w == null) {
            return null;
        }
        String head = depth == 0 ? OPEN_SENTINEL : "_";
        return prependStr(head, w);
    }

    // ---------------------------------------------------------------- matrix specialization helpers

    private static List<List<Pat>> expandOrInCol0(List<List<Pat>> matrix) {
        List<List<Pat>> out = new ArrayList<>(matrix.size());
        for (List<Pat> row : matrix) {
            expandOrRow(row.get(0), row.subList(1, row.size()), out);
        }
        return out;
    }

    private static void expandOrRow(Pat head, List<Pat> rest, List<List<Pat>> out) {
        if (head instanceof Pat.Or or) {
            for (Pat alt : or.alts()) {
                expandOrRow(alt, rest, out);
            }
        } else {
            out.add(prepend(head, rest));
        }
    }

    /** Adapts a single pattern in {@code q} or {@code P} to a specific subtype {@code leaf} of a sum. */
    private Pat adaptPatForSubtype(Pat pat, Type leaf) {
        Type leafBase = TypeAlgebra.unwrapFrozen(leaf);
        return switch (pat) {
            case Pat.Wildcard w -> w;
            case Pat.Opaque o -> o;
            case Pat.NullConst nc -> leafBase == Type.NULL ? Pat.Wildcard.INSTANCE : null;
            case Pat.BoolConst bc -> (leafBase == Type.BOOL || leafBase == Type.ANY) ? bc : null;
            case Pat.EnumConst ec -> (leafBase == Type.ANY
                    || (leafBase instanceof Type.JavaInstance ji && ji.type().isAssignableFrom(ec.enumClass())))
                    ? ec : null;
            case Pat.ScalarConst sc -> scalarOverlapsType(sc, leafBase) ? sc : null;
            case Pat.ConstRange cr -> rangeOverlapsType(cr, leafBase) ? cr : null;
            case Pat.TypePat tp -> {
                if (typeCovers(tp.target(), leafBase)) {
                    yield Pat.Wildcard.INSTANCE;
                }
                if (overlapsTarget(leafBase, tp.target())) {
                    yield tp;
                }
                yield null;
            }
            case Pat.RecordPat rp -> recordMatchesType(rp, leafBase) ? rp : null;
            case Pat.SeqPat sp -> seqMatchesType(sp, leafBase) ? sp : null;
            case Pat.Or or -> {
                List<Pat> kept = new ArrayList<>();
                for (Pat a : or.alts()) {
                    Pat adapted = adaptPatForSubtype(a, leaf);
                    if (adapted != null) {
                        kept.add(adapted);
                    }
                }
                yield kept.isEmpty() ? null : (kept.size() == 1 ? kept.get(0) : new Pat.Or(kept));
            }
        };
    }

    private List<List<Pat>> filterMatrixForSubtype(List<List<Pat>> expandedP, Type leaf) {
        List<List<Pat>> out = new ArrayList<>();
        for (List<Pat> row : expandedP) {
            Pat head = row.get(0);
            if (head instanceof Pat.Opaque) {
                continue;
            }
            Pat adapted = adaptPatForSubtype(head, leaf);
            if (adapted != null) {
                out.add(prepend(adapted, row.subList(1, row.size())));
            }
        }
        return out;
    }

    private boolean matchesNull(Pat p) {
        return p instanceof Pat.Wildcard || p instanceof Pat.NullConst || p instanceof Pat.Opaque
                || (p instanceof Pat.TypePat tp && Type.containsNull(tp.target()));
    }

    private List<List<Pat>> specializeNull(List<List<Pat>> expandedP) {
        List<List<Pat>> out = new ArrayList<>();
        for (List<Pat> row : expandedP) {
            Pat h = row.get(0);
            if (h instanceof Pat.Wildcard || h instanceof Pat.NullConst
                    || (h instanceof Pat.TypePat tp && Type.containsNull(tp.target()))) {
                out.add(row.subList(1, row.size()));
            }
        }
        return out;
    }

    private List<List<Pat>> specializeBool(List<List<Pat>> expandedP, boolean value) {
        List<List<Pat>> out = new ArrayList<>();
        for (List<Pat> row : expandedP) {
            Pat h = row.get(0);
            if (h instanceof Pat.Wildcard
                    || (h instanceof Pat.BoolConst bc && bc.value() == value)
                    || (h instanceof Pat.TypePat tp && typeCovers(tp.target(), Type.BOOL))) {
                out.add(row.subList(1, row.size()));
            }
        }
        return out;
    }

    private List<List<Pat>> specializeEnum(List<List<Pat>> expandedP, Class<?> enumClass, String name) {
        Type enumType = new Type.JavaInstance(enumClass, List.of());
        List<List<Pat>> out = new ArrayList<>();
        for (List<Pat> row : expandedP) {
            Pat h = row.get(0);
            if (h instanceof Pat.Wildcard
                    || (h instanceof Pat.EnumConst ec && ec.enumClass() == enumClass && ec.name().equals(name))
                    || (h instanceof Pat.TypePat tp && typeCovers(tp.target(), enumType))) {
                out.add(row.subList(1, row.size()));
            }
        }
        return out;
    }

    private boolean hasRecordPatInCol0(List<List<Pat>> expandedP, Type base0) {
        for (List<Pat> row : expandedP) {
            if (row.get(0) instanceof Pat.RecordPat rp && recordMatchesType(rp, base0)) {
                return true;
            }
        }
        return false;
    }

    private List<Pat> specializeRecordPat(Pat pat, Type base0, int arity) {
        if (pat instanceof Pat.Wildcard || pat instanceof Pat.Opaque
                || (pat instanceof Pat.TypePat tp && typeCovers(tp.target(), base0))) {
            return nWildcards(arity);
        }
        if (pat instanceof Pat.RecordPat rp && recordMatchesType(rp, base0)) {
            if (rp.fields().size() == arity) {
                return rp.fields();
            }
            List<Pat> padded = new ArrayList<>(arity);
            for (int i = 0; i < arity; i++) {
                padded.add(i < rp.fields().size() ? rp.fields().get(i) : Pat.Wildcard.INSTANCE);
            }
            return padded;
        }
        return null;
    }

    private List<List<Pat>> specializeRecordMatrix(List<List<Pat>> expandedP, Type base0, int arity) {
        List<List<Pat>> out = new ArrayList<>();
        for (List<Pat> row : expandedP) {
            Pat h = row.get(0);
            if (h instanceof Pat.Opaque) {
                continue;
            }
            List<Pat> fields = specializeRecordPat(h, base0, arity);
            if (fields != null) {
                out.add(concatPats(fields, row.subList(1, row.size())));
            }
        }
        return out;
    }

    private boolean hasSeqPatInCol0(List<List<Pat>> expandedP, Type base0) {
        for (List<Pat> row : expandedP) {
            if (row.get(0) instanceof Pat.SeqPat sp && seqMatchesType(sp, base0)) {
                return true;
            }
        }
        return false;
    }

    private int computeSequenceSplitThreshold(List<List<Pat>> expandedP, Pat q0, boolean isArray, Type base0) {
        int maxExact = -1;
        int maxRest = 0;
        for (List<Pat> row : expandedP) {
            if (row.get(0) instanceof Pat.SeqPat sp && sp.isArray() == isArray && seqMatchesType(sp, base0)) {
                if (sp.hasRest()) {
                    maxRest = Math.max(maxRest, sp.elems().size());
                } else {
                    maxExact = Math.max(maxExact, sp.elems().size());
                }
            }
        }
        if (q0 instanceof Pat.SeqPat sp && sp.isArray() == isArray && seqMatchesType(sp, base0)) {
            if (sp.hasRest()) {
                maxRest = Math.max(maxRest, sp.elems().size());
            } else {
                maxExact = Math.max(maxExact, sp.elems().size());
            }
        }
        return Math.max(maxExact + 1, maxRest);
    }

    private List<Pat> specializeSeqExactPat(Pat pat, boolean isArray, Type base0, int k) {
        if (pat instanceof Pat.Wildcard || pat instanceof Pat.Opaque
                || (pat instanceof Pat.TypePat tp && typeCovers(tp.target(), base0))) {
            return nWildcards(k);
        }
        if (pat instanceof Pat.SeqPat sp && sp.isArray() == isArray && seqMatchesType(sp, base0)) {
            if (!sp.hasRest()) {
                return sp.elems().size() == k ? sp.elems() : null;
            }
            if (sp.elems().size() <= k) {
                return padWithWildcards(sp.elems(), k);
            }
        }
        return null;
    }

    private List<List<Pat>> specializeSeqExactMatrix(List<List<Pat>> expandedP, boolean isArray, Type base0, int k) {
        List<List<Pat>> out = new ArrayList<>();
        for (List<Pat> row : expandedP) {
            Pat h = row.get(0);
            if (h instanceof Pat.Opaque) {
                continue;
            }
            List<Pat> elems = specializeSeqExactPat(h, isArray, base0, k);
            if (elems != null) {
                out.add(concatPats(elems, row.subList(1, row.size())));
            }
        }
        return out;
    }

    private List<Pat> specializeSeqAtLeastPat(Pat pat, boolean isArray, Type base0, int minLen) {
        if (pat instanceof Pat.Wildcard || pat instanceof Pat.Opaque
                || (pat instanceof Pat.TypePat tp && typeCovers(tp.target(), base0))) {
            return nWildcards(minLen);
        }
        if (pat instanceof Pat.SeqPat sp && sp.isArray() == isArray && seqMatchesType(sp, base0)
                && sp.hasRest() && sp.elems().size() <= minLen) {
            return padWithWildcards(sp.elems(), minLen);
        }
        return null;
    }

    private List<List<Pat>> specializeSeqAtLeastMatrix(List<List<Pat>> expandedP, boolean isArray, Type base0, int minLen) {
        List<List<Pat>> out = new ArrayList<>();
        for (List<Pat> row : expandedP) {
            Pat h = row.get(0);
            if (h instanceof Pat.Opaque) {
                continue;
            }
            List<Pat> elems = specializeSeqAtLeastPat(h, isArray, base0, minLen);
            if (elems != null) {
                out.add(concatPats(elems, row.subList(1, row.size())));
            }
        }
        return out;
    }

    private List<List<Pat>> defaultMatrix(List<List<Pat>> expandedP, Type base0) {
        List<List<Pat>> out = new ArrayList<>();
        for (List<Pat> row : expandedP) {
            Pat h = row.get(0);
            if (h instanceof Pat.Wildcard || (h instanceof Pat.TypePat tp && typeCovers(tp.target(), base0))) {
                out.add(row.subList(1, row.size()));
            }
        }
        return out;
    }

    private List<List<Pat>> defaultMatrixCovering(List<List<Pat>> expandedP, Type target) {
        List<List<Pat>> out = new ArrayList<>();
        for (List<Pat> row : expandedP) {
            Pat h = row.get(0);
            if (h instanceof Pat.Wildcard || (h instanceof Pat.TypePat tp && typeCovers(tp.target(), target))) {
                out.add(row.subList(1, row.size()));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- open-domain constant & range shadowing

    private boolean rowCoversScalar(Pat p, Pat.ScalarConst q, Type base0) {
        if (p instanceof Pat.Wildcard) {
            return true;
        }
        if (p instanceof Pat.TypePat tp) {
            Type qType = scalarType(q);
            return qType != null ? typeCovers(tp.target(), qType) : typeCovers(tp.target(), base0);
        }
        if (p instanceof Pat.ScalarConst pc) {
            return scalarValuesEqual(pc.value(), q.value());
        }
        if (p instanceof Pat.ConstRange pr) {
            return rangeContainsScalar(pr, q);
        }
        return false;
    }

    private boolean rowCoversRange(Pat p, Pat.ConstRange q, Type base0) {
        if (p instanceof Pat.Wildcard) {
            return true;
        }
        if (p instanceof Pat.TypePat tp) {
            return typeCovers(tp.target(), base0);
        }
        if (p instanceof Pat.ConstRange pr) {
            return rangeSubsumes(pr, q);
        }
        return false;
    }

    private static boolean scalarValuesEqual(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            if (na instanceof Double || na instanceof Float || nb instanceof Double || nb instanceof Float) {
                return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
            }
            return na.longValue() == nb.longValue();
        }
        return a.equals(b);
    }

    private static boolean rangeContainsScalar(Pat.ConstRange r, Pat.ScalarConst s) {
        if (r.isChar()) {
            if (!(s.value() instanceof Character ch)) {
                return false;
            }
            long v = ch;
            return discreteContains(r, v);
        }
        if (r.isDouble()) {
            if (!(s.value() instanceof Number n)) {
                return false;
            }
            double v = n.doubleValue();
            if (Double.isNaN(v)) {
                return false;
            }
            return Double.compare(v, r.dLo()) >= 0
                    && (r.inclusive() ? Double.compare(v, r.dHi()) <= 0 : Double.compare(v, r.dHi()) < 0);
        }
        if (!(s.value() instanceof Number n)) {
            return false;
        }
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Math.rint(d) != d) {
                return false;
            }
            return discreteContains(r, (long) d);
        }
        return discreteContains(r, n.longValue());
    }

    private static boolean discreteContains(Pat.ConstRange r, long v) {
        if (r.step() <= 0) {
            return false;
        }
        long hi = effectiveDiscreteHi(r);
        if (hi < r.lo() || v < r.lo() || v > hi) {
            return false;
        }
        return r.step() == 1 || ((v - r.lo()) % r.step() == 0);
    }

    private static long effectiveDiscreteHi(Pat.ConstRange r) {
        if (r.inclusive()) {
            return r.hi();
        }
        return r.hi() == Long.MIN_VALUE ? Long.MIN_VALUE : r.hi() - 1;
    }

    private static boolean rangeSubsumes(Pat.ConstRange outer, Pat.ConstRange inner) {
        if (outer.isChar() != inner.isChar()) {
            return false;
        }
        if (outer.isDouble() || inner.isDouble()) {
            double oLo = outer.isDouble() ? outer.dLo() : outer.lo();
            double oHi = outer.isDouble() ? outer.dHi() : outer.hi();
            double iLo = inner.isDouble() ? inner.dLo() : inner.lo();
            double iHi = inner.isDouble() ? inner.dHi() : inner.hi();
            if (Double.isNaN(oLo) || Double.isNaN(oHi) || Double.isNaN(iLo) || Double.isNaN(iHi)) {
                return false;
            }
            if (iLo > iHi || (!inner.inclusive() && iLo >= iHi)) {
                return false;
            }
            if (!outer.isDouble() && outer.step() != 1) {
                return false;
            }
            return Double.compare(oLo, iLo) <= 0
                    && (Double.compare(iHi, oHi) < 0
                        || (Double.compare(iHi, oHi) == 0 && (outer.inclusive() || !inner.inclusive())));
        }
        if (outer.step() <= 0 || inner.step() <= 0) {
            return false;
        }
        long oHi = effectiveDiscreteHi(outer);
        long iHi = effectiveDiscreteHi(inner);
        if (inner.lo() > iHi || outer.lo() > oHi) {
            return false;
        }
        if (outer.lo() > inner.lo() || iHi > oHi) {
            return false;
        }
        if (outer.step() == 1) {
            return true;
        }
        return ((inner.lo() - outer.lo()) % outer.step() == 0) && (inner.step() % outer.step() == 0);
    }

    /** Checks whether step-1 discrete ranges + scalar constants in column 0 cover all integers in {@code q}. */
    private static boolean isCoveredByDiscreteUnion(List<List<Pat>> expandedP, Pat.ConstRange q) {
        long qLo = q.lo();
        long qHi = effectiveDiscreteHi(q);
        if (qLo > qHi) {
            return false;
        }
        List<long[]> intervals = new ArrayList<>();
        for (List<Pat> row : expandedP) {
            Pat h = row.get(0);
            if (h instanceof Pat.ConstRange pr && pr.isChar() == q.isChar() && !pr.isDouble() && pr.step() == 1) {
                long lo = pr.lo();
                long hi = effectiveDiscreteHi(pr);
                if (lo <= hi && hi >= qLo && lo <= qHi) {
                    intervals.add(new long[]{Math.max(lo, qLo), Math.min(hi, qHi)});
                }
            } else if (h instanceof Pat.ScalarConst sc) {
                Long v = discreteScalarVal(sc, q.isChar());
                if (v != null && v >= qLo && v <= qHi) {
                    intervals.add(new long[]{v, v});
                }
            }
        }
        if (intervals.isEmpty()) {
            return false;
        }
        intervals.sort(Comparator.comparingLong(a -> a[0]));
        long coveredUpTo = qLo - 1;
        for (long[] iv : intervals) {
            if (iv[0] > coveredUpTo + 1) {
                return false;
            }
            if (iv[1] > coveredUpTo) {
                coveredUpTo = iv[1];
                if (coveredUpTo >= qHi) {
                    return true;
                }
            }
        }
        return coveredUpTo >= qHi;
    }

    private static Long discreteScalarVal(Pat.ScalarConst sc, boolean isChar) {
        if (isChar) {
            return sc.value() instanceof Character c ? (long) c : null;
        }
        if (sc.value() instanceof Byte || sc.value() instanceof Short
                || sc.value() instanceof Integer || sc.value() instanceof Long) {
            return ((Number) sc.value()).longValue();
        }
        return null;
    }

    // ---------------------------------------------------------------- type & predicate helpers

    private boolean typeCovers(Type target, Type sub) {
        if (target == null || sub == null || target == Type.ERROR || sub == Type.ERROR) {
            return false;
        }
        Type subBase = TypeAlgebra.unwrapFrozen(sub);
        Type tgtBase = TypeAlgebra.unwrapFrozen(target);
        if (subBase == Type.NULL) {
            return Type.containsNull(tgtBase);
        }
        if (tgtBase == Type.ANY) {
            return true;
        }
        if (tgtBase instanceof Type.Union u) {
            for (Type m : u.members()) {
                if (typeCovers(m, subBase)) {
                    return true;
                }
            }
            return false;
        }
        if (algebra.runtimeSubtype(subBase, tgtBase)) {
            return true;
        }
        // `is` on non-array classes/interfaces erases generic type arguments (`is Box` covers `Box of int`,
        // `is list` covers `list of int`).
        if (subBase instanceof Type.Instance si && tgtBase instanceof Type.Instance ti) {
            return algebra.projectToBabble(si, ti.node()) != null;
        }
        if (subBase instanceof Type.JavaInstance sj && tgtBase instanceof Type.JavaInstance tj) {
            return tj.type().isAssignableFrom(sj.type());
        }
        return false;
    }

    private boolean overlapsTarget(Type a, Type b) {
        if (a == Type.NULL) {
            return Type.containsNull(b);
        }
        if (b == Type.NULL) {
            return Type.containsNull(a);
        }
        return algebra.overlapsErased(TypeAlgebra.unwrapFrozen(a), TypeAlgebra.unwrapFrozen(b));
    }

    private Type narrowColType(Type current, Type target) {
        if (target == null || target == Type.ERROR) {
            return null;
        }
        return algebra.narrowTarget(current, target);
    }

    private static boolean isRecordType(Type base) {
        return (base instanceof Type.Instance inst && inst.node().kind == ClassShape.Kind.VALUE_CLASS)
                || (base instanceof Type.JavaInstance ji && ji.type().isRecord());
    }

    private static String recordSimpleName(Type base) {
        if (base instanceof Type.Instance inst) {
            return inst.node().name;
        }
        if (base instanceof Type.JavaInstance ji) {
            return ji.type().getSimpleName();
        }
        return TypeDisplay.display(base);
    }

    private boolean recordMatchesType(Pat.RecordPat rp, Type base) {
        if (base == Type.ANY) {
            return true;
        }
        if (!rp.isJavaRecord()) {
            if (base instanceof Type.Instance inst) {
                return inst.node() == rp.babbleNode()
                        || algebra.projectToBabble(new Type.Instance(rp.babbleNode(), List.of()), inst.node()) != null;
            }
            return algebra.overlapsErased(base, rp.recordType());
        }
        if (base instanceof Type.JavaInstance ji) {
            return ji.type() == rp.javaRecordClass() || ji.type().isAssignableFrom(rp.javaRecordClass());
        }
        return algebra.overlapsErased(base, rp.recordType());
    }

    private boolean isSequenceType(Type base) {
        return base instanceof Type.Array
                || (base instanceof Type.JavaInstance ji && List.class.isAssignableFrom(ji.type()));
    }

    private Type sequenceElementType(Type seqType) {
        boolean isFrozen = seqType instanceof Type.Frozen;
        Type base = TypeAlgebra.unwrapFrozen(seqType);
        Type elem = Type.NULLABLE_ANY;
        if (base instanceof Type.Array arr) {
            elem = arr.element();
        } else {
            Type projected = algebra.projectToJava(base, List.class);
            if (projected instanceof Type.JavaInstance ji && !ji.typeArguments().isEmpty()) {
                elem = ji.typeArguments().get(0);
            }
        }
        return isFrozen ? Type.frozen(elem) : elem;
    }

    private boolean seqMatchesType(Pat.SeqPat sp, Type base) {
        if (base == Type.ANY) {
            return true;
        }
        if (sp.isArray()) {
            if (!(base instanceof Type.Array)) {
                return false;
            }
            return sp.targetType() == null || algebra.overlapsErased(base, sp.targetType());
        }
        return base instanceof Type.JavaInstance ji
                && (List.class.isAssignableFrom(ji.type()) || ji.type().isAssignableFrom(List.class));
    }

    private static Type scalarType(Pat.ScalarConst sc) {
        if (sc.value() instanceof String) {
            return Type.STRING;
        }
        return switch (sc.kind()) {
            case BYTE -> Type.BYTE;
            case SHORT -> Type.SHORT;
            case INT -> Type.INT;
            case LONG -> Type.LONG;
            case FLOAT -> Type.FLOAT;
            case DOUBLE -> Type.DOUBLE;
            case CHAR -> Type.CHAR;
            case BOOL -> Type.BOOL;
            default -> null;
        };
    }

    private boolean scalarOverlapsType(Pat.ScalarConst sc, Type leafBase) {
        if (leafBase == Type.ANY) {
            return true;
        }
        if (leafBase == Type.NULL) {
            return false;
        }
        Type st = scalarType(sc);
        if (st == null) {
            return true;
        }
        if (Type.isNumericScalar(st) && Type.isNumericScalar(leafBase)) {
            return true;
        }
        return algebra.overlapsErased(leafBase, st);
    }

    private static boolean rangeOverlapsType(Pat.ConstRange cr, Type leafBase) {
        if (leafBase == Type.ANY) {
            return true;
        }
        if (cr.isChar()) {
            return leafBase == Type.CHAR;
        }
        return Type.isNumericScalar(leafBase);
    }

    private static String formatTypeWitness(Type type) {
        Type base = TypeAlgebra.unwrapFrozen(type);
        if (base == Type.NULL) {
            return "null";
        }
        if (base instanceof Type.Instance inst) {
            return inst.node().name;
        }
        if (base instanceof Type.JavaInstance ji) {
            return ji.type().getSimpleName();
        }
        return TypeDisplay.display(base);
    }

    private static String formatSeqWitness(String seqName, List<String> w, int len) {
        if (len == 0) {
            return seqName + "()";
        }
        List<String> elems = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            String ew = w.get(i);
            elems.add(OPEN_SENTINEL.equals(ew) ? "_" : ew);
        }
        return seqName + "(" + String.join(", ", elems) + ")";
    }

    // ---------------------------------------------------------------- small list utilities

    private static List<Pat> nWildcards(int n) {
        List<Pat> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(Pat.Wildcard.INSTANCE);
        }
        return out;
    }

    private static List<Pat> padWithWildcards(List<Pat> prefix, int targetLen) {
        if (prefix.size() == targetLen) {
            return prefix;
        }
        List<Pat> out = new ArrayList<>(targetLen);
        out.addAll(prefix);
        for (int i = prefix.size(); i < targetLen; i++) {
            out.add(Pat.Wildcard.INSTANCE);
        }
        return out;
    }

    private static List<Pat> prepend(Pat head, List<Pat> tail) {
        List<Pat> out = new ArrayList<>(tail.size() + 1);
        out.add(head);
        out.addAll(tail);
        return out;
    }

    private static List<Pat> concatPats(List<Pat> first, List<Pat> second) {
        List<Pat> out = new ArrayList<>(first.size() + second.size());
        out.addAll(first);
        out.addAll(second);
        return out;
    }

    private static List<Type> prependType(Type head, List<Type> tail) {
        List<Type> out = new ArrayList<>(tail.size() + 1);
        out.add(head);
        out.addAll(tail);
        return out;
    }

    private static List<Type> concatTypes(List<Type> first, List<Type> second) {
        List<Type> out = new ArrayList<>(first.size() + second.size());
        out.addAll(first);
        out.addAll(second);
        return out;
    }

    private static List<Type> prependRepeatedType(Type elem, int count, List<Type> tail) {
        List<Type> out = new ArrayList<>(count + tail.size());
        for (int i = 0; i < count; i++) {
            out.add(elem);
        }
        out.addAll(tail);
        return out;
    }

    private static List<String> prependStr(String head, List<String> tail) {
        List<String> out = new ArrayList<>(tail.size() + 1);
        out.add(head);
        out.addAll(tail);
        return out;
    }

    private static List<String> replaceHead(List<String> list, String newHead) {
        List<String> out = new ArrayList<>(list);
        out.set(0, newHead);
        return out;
    }
}
