package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
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
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Operators;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compile-time effect, parameter/receiver mutability, borrow-escape, static {@code mut}
 * exclusivity, flow-sensitive {@code nothrow}, and compositional algebraic checker (BIP007-I1).
 */
final class EffectChecker {

    private static final int MAX_SPECIALIZATION_DEPTH = 4;

    private enum ParseContext {
        FUNCTION_LITERAL,
        FUNCTION_TYPE,
        INTERFACE_METHOD,
        JAVA_TARGET
    }

    record ParsedPrefix(EnumSet<Effect> effects,
                        boolean mutThis,
                        boolean borrowThis,
                        boolean explicitSharedThis,
                        boolean uniqueReturn,
                        boolean axiom,
                        boolean io,
                        boolean explicitVolatile,
                        boolean hasAnyModifier) {
        static final ParsedPrefix EMPTY = new ParsedPrefix(
                EnumSet.noneOf(Effect.class), false, false, false, false, false, false, false, false);

        EffectSet toEffectSet() {
            return EffectSet.ofDeclared(effects, mutThis, borrowThis, explicitSharedThis,
                    uniqueReturn, io, explicitVolatile, axiom);
        }
    }

    record ParamMods(boolean mut, boolean shared, boolean unique) {
        static final ParamMods NONE = new ParamMods(false, false, false);
    }

    private static final BitSet EMPTY_SUMMARY_BITS = new BitSet(0);

    record CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams, boolean[] uniqueParams,
                           Set<BabbleParser.FunctionLiteralContext> frameReadOwners,
                           Set<BabbleParser.FunctionLiteralContext> frameWriteOwners,
                           boolean deepMutThis,
                           BitSet returnParams, boolean returnThis, boolean returnExternal, boolean returnKnown,
                           BitSet ctorStoredParams,
                           boolean[] readParams, boolean readsThis) {
        CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams, boolean[] uniqueParams,
                        Set<BabbleParser.FunctionLiteralContext> frameReadOwners,
                        Set<BabbleParser.FunctionLiteralContext> frameWriteOwners,
                        boolean deepMutThis,
                        BitSet returnParams, boolean returnThis, boolean returnExternal, boolean returnKnown,
                        BitSet ctorStoredParams) {
            this(effects, mutParams, borrowParams, uniqueParams, frameReadOwners, frameWriteOwners, deepMutThis,
                    returnParams, returnThis, returnExternal, returnKnown, ctorStoredParams,
                    new boolean[mutParams.length], false);
        }

        CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams, boolean[] uniqueParams,
                        Set<BabbleParser.FunctionLiteralContext> frameReadOwners,
                        Set<BabbleParser.FunctionLiteralContext> frameWriteOwners,
                        boolean deepMutThis) {
            this(effects, mutParams, borrowParams, uniqueParams, frameReadOwners, frameWriteOwners, deepMutThis,
                    EMPTY_SUMMARY_BITS, false, false, false, EMPTY_SUMMARY_BITS,
                    new boolean[mutParams.length], false);
        }

        CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams, boolean[] uniqueParams,
                        Set<BabbleParser.FunctionLiteralContext> frameReadOwners,
                        Set<BabbleParser.FunctionLiteralContext> frameWriteOwners) {
            this(effects, mutParams, borrowParams, uniqueParams, frameReadOwners, frameWriteOwners, false,
                    EMPTY_SUMMARY_BITS, false, false, false, EMPTY_SUMMARY_BITS,
                    new boolean[mutParams.length], false);
        }

        CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams,
                        Set<BabbleParser.FunctionLiteralContext> frameReadOwners,
                        Set<BabbleParser.FunctionLiteralContext> frameWriteOwners) {
            this(effects, mutParams, borrowParams, new boolean[mutParams.length], frameReadOwners, frameWriteOwners, false,
                    EMPTY_SUMMARY_BITS, false, false, false, EMPTY_SUMMARY_BITS,
                    new boolean[mutParams.length], false);
        }

        CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams, boolean[] uniqueParams) {
            this(effects, mutParams, borrowParams, uniqueParams, Set.of(), Set.of(), false,
                    EMPTY_SUMMARY_BITS, false, false, false, EMPTY_SUMMARY_BITS,
                    new boolean[mutParams.length], false);
        }

        CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams) {
            this(effects, mutParams, borrowParams, new boolean[mutParams.length], Set.of(), Set.of(), false,
                    EMPTY_SUMMARY_BITS, false, false, false, EMPTY_SUMMARY_BITS,
                    new boolean[mutParams.length], false);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CallableSummary that)) {
                return false;
            }
            return deepMutThis == that.deepMutThis
                    && returnThis == that.returnThis
                    && returnExternal == that.returnExternal
                    && returnKnown == that.returnKnown
                    && readsThis == that.readsThis
                    && Objects.equals(effects, that.effects)
                    && Arrays.equals(mutParams, that.mutParams)
                    && Arrays.equals(borrowParams, that.borrowParams)
                    && Arrays.equals(uniqueParams, that.uniqueParams)
                    && Arrays.equals(readParams, that.readParams)
                    && Objects.equals(frameReadOwners, that.frameReadOwners)
                    && Objects.equals(frameWriteOwners, that.frameWriteOwners)
                    && Objects.equals(returnParams, that.returnParams)
                    && Objects.equals(ctorStoredParams, that.ctorStoredParams);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(effects, frameReadOwners, frameWriteOwners, deepMutThis,
                    returnParams, returnThis, returnExternal, returnKnown, ctorStoredParams, readsThis);
            result = 31 * result + Arrays.hashCode(mutParams);
            result = 31 * result + Arrays.hashCode(borrowParams);
            result = 31 * result + Arrays.hashCode(uniqueParams);
            result = 31 * result + Arrays.hashCode(readParams);
            return result;
        }
    }

    record JavaCallProfile(EffectSet effects, boolean mutReceiver, boolean borrowReceiver,
                           boolean[] mutParams, boolean[] borrowParams, boolean[] uniqueParams,
                           boolean explicitNothrow, boolean view, boolean shallow, boolean fresh,
                           boolean readsReceiver, boolean[] readParams) {
        JavaCallProfile(EffectSet effects, boolean mutReceiver, boolean borrowReceiver,
                        boolean[] mutParams, boolean[] borrowParams, boolean[] uniqueParams,
                        boolean explicitNothrow, boolean view, boolean shallow, boolean fresh) {
            this(effects, mutReceiver, borrowReceiver, mutParams, borrowParams, uniqueParams, explicitNothrow,
                    view, shallow, fresh, true, allTrue(mutParams.length));
        }

        JavaCallProfile(EffectSet effects, boolean mutReceiver, boolean borrowReceiver,
                        boolean[] mutParams, boolean[] borrowParams, boolean[] uniqueParams,
                        boolean explicitNothrow) {
            this(effects, mutReceiver, borrowReceiver, mutParams, borrowParams, uniqueParams, explicitNothrow,
                    false, false, false, true, allTrue(mutParams.length));
        }

        JavaCallProfile(EffectSet effects, boolean mutReceiver, boolean borrowReceiver,
                        boolean[] mutParams, boolean[] borrowParams, boolean explicitNothrow) {
            this(effects, mutReceiver, borrowReceiver, mutParams, borrowParams, new boolean[mutParams.length], explicitNothrow,
                    false, false, false, true, allTrue(mutParams.length));
        }

        private static boolean[] allTrue(int n) {
            boolean[] arr = new boolean[n];
            Arrays.fill(arr, true);
            return arr;
        }
    }

    /** Tracks alias provenance and direct binding identity for static mut/borrow/exclusivity checks. */
    private static final class Origin {
        private static final BitSet EMPTY_BITS = new BitSet(0);
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

    private record PathKey(Object root, List<String> segments) {
        PathKey append(String field) {
            List<String> next = new ArrayList<>(segments.size() + 1);
            next.addAll(segments);
            next.add(field);
            return new PathKey(root, List.copyOf(next));
        }
    }

    private static final Object THIS_KEY = new Object();

    private final BlockScopes pass;
    private final TypedProgram checker;

    /** File, session, and imported-module Java target overrides (tier 1 / tier 4). */
    private final Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> localJavaEffects = new LinkedHashMap<>();
    /** Top-level Java overrides committed when this input belongs to a REPL session. */
    private final Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> committedJavaEffects = new LinkedHashMap<>();
    /** Exported Java overrides published on {@link ModuleInterface}. */
    private final Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects = new LinkedHashMap<>();
    /** BIP019 structured axioms from imports, session, and this file (tier 1). */
    private AxiomData.AxiomTable localAxioms = AxiomData.AxiomTable.EMPTY;
    /** Top-level BIP019 axioms committed when this input belongs to a REPL session. */
    private AxiomData.AxiomTable committedAxioms = AxiomData.AxiomTable.EMPTY;
    /** Exported BIP019 axioms published on {@link ModuleInterface}. */
    private AxiomData.AxiomTable exportedAxioms = AxiomData.AxiomTable.EMPTY;
    /** Diagnostics recorded while pre-parsing {@code axiomExpressions} before inference. */
    private final List<PendingAxiomDiag> pendingAxiomDiags = new ArrayList<>();
    /** Defs that carry {@code = axiom ...} or {@code = impure ...} on non-function-literal values. */
    private final Map<BabbleParser.DefExpressionContext, ParsedPrefix> defPrefixes = new IdentityHashMap<>();

    private record PendingAxiomDiag(String message, ParserRuleContext at) {
    }

    private final Map<BabbleParser.FunctionLiteralContext, ParsedPrefix> fnPrefixes = new IdentityHashMap<>();
    private final Map<ParserRuleContext, ParamMods> paramModsMap = new IdentityHashMap<>();
    private final Set<ParseTree> validatedTypeTrees = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Closures proven not to escape their enclosing function {@code F} (only called or passed to borrow slots). */
    private final Map<BabbleParser.FunctionLiteralContext, BabbleParser.FunctionLiteralContext> frameLocalOwner = new IdentityHashMap<>();
    /** Local defs of {@code F} captured by an *escaping* closure (cannot be treated as private frame state). */
    private final Set<ParserRuleContext> escapedCapturedLocals = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Converged internal summaries for all callables (FunctionLiteralContext and ClassExpressionContext). */
    private final Map<ParserRuleContext, CallableSummary> summaries = new IdentityHashMap<>();
    private final Map<BabbleParser.WhileExpressionContext, Boolean> loopNothrow = new IdentityHashMap<>();
    /** Caller-visible summaries (locked to declared contract for annotated callables per BIP007-D2). */
    private final Map<ParserRuleContext, CallableSummary> visibleSummaries = new IdentityHashMap<>();
    /** Callables belonging to a cyclic SCC (self-recursive or mutually recursive) — cannot be @TERMINATING. */
    private final Set<ParserRuleContext> cyclicCallables = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Active call-site specialization stack to bound recursive higher-order specialization. */
    private final Deque<SpecKey> activeSpecializations = new ArrayDeque<>();
    private final Map<SpecKey, CallableSummary> specializationCache = new HashMap<>();

    private record SpecKey(BabbleParser.FunctionLiteralContext fn, List<Object> callbackBindings) {
    }

    EffectChecker(BlockScopes pass, TypedProgram checker) {
        this.pass = pass;
        this.checker = checker;
        // Seed from statically imported modules (export axiom / export impure) and REPL session.
        for (ModuleInterface module : pass.modules.values()) {
            localJavaEffects.putAll(module.exportedJavaEffects());
            localAxioms = localAxioms.mergeOver(module.exportedAxioms());
        }
        if (pass.session != null) {
            localJavaEffects.putAll(pass.session.javaEffects);
            localAxioms = localAxioms.mergeOver(pass.session.axioms);
            visibleSummaries.putAll(pass.session.callableSummaries);
        }
        collectAxiomExpressions();
    }

    AxiomData.AxiomTable localAxioms() {
        return localAxioms;
    }

    AxiomData.AxiomTable exportedAxioms() {
        return exportedAxioms;
    }

    AxiomData.AxiomTable committedAxioms() {
        return committedAxioms;
    }

    boolean isJavaClassDeeplyImmutable(Class<?> cls) {
        if (cls == null) {
            return false;
        }
        if (!localAxioms.isEmpty()
                && AxiomData.lookupClassFlag(cls, localAxioms::directClassAxiom, AxiomData.ClassAxiom::immutable)) {
            return true;
        }
        return pass.hostEffects.builtinAxioms().isDeeplyImmutable(cls);
    }

    Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects() {
        return exportedJavaEffects.isEmpty() ? Map.of() : Collections.unmodifiableMap(exportedJavaEffects);
    }

    Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> committedJavaEffects() {
        return committedJavaEffects.isEmpty() ? Map.of() : Collections.unmodifiableMap(committedJavaEffects);
    }

    Map<ParserRuleContext, CallableSummary> committedCallableSummaries() {
        return visibleSummaries.isEmpty() ? Map.of() : Collections.unmodifiableMap(visibleSummaries);
    }

    // ================================================================ 1. Parsing Modifiers

    boolean isCompletelyUntyped(BabbleParser.FunctionLiteralContext fn) {
        if (fn.effectPrefix() != null || fn.type() != null || fn.uniqueReturn != null) {
            return false;
        }
        if (isExportOrInterfaceMethod(fn)) {
            return false;
        }
        List<BabbleParser.FnParamContext> params = fn.fnParameters().fnParam();
        for (BabbleParser.FnParamContext param : params) {
            if (param.type() != null || !ScopeAnalysis.paramModifiers(param).isEmpty()) {
                return false;
            }
        }
        // A named `def f = fn () -> (...)` has zero untyped parameters (it simply omits the void/null return type);
        // treat it as a normal typed function (pure by default per BIP016-D3), while anonymous inline lambdas and
        // functions with untyped parameters infer effects.
        if (params.isEmpty() && isNamedDefRhs(fn)) {
            return false;
        }
        return true;
    }

    private static boolean isNamedDefRhs(BabbleParser.FunctionLiteralContext fn) {
        ParserRuleContext cur = fn;
        while ((cur.getParent() instanceof BabbleParser.BlockExpressionContext blk
                && blk.block().expression().size() == 1)
                || cur.getParent() instanceof BabbleParser.OverloadExpressionContext) {
            cur = cur.getParent();
        }
        return cur.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == cur;
    }

    private boolean isInterfaceDefault(BabbleParser.FunctionLiteralContext fn) {
        ParserRuleContext cur = fn;
        while ((cur.getParent() instanceof BabbleParser.BlockExpressionContext blk
                && blk.block().expression().size() == 1)
                || cur.getParent() instanceof BabbleParser.OverloadExpressionContext) {
            cur = cur.getParent();
        }
        if (cur.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == cur) {
            ScopeAnalysis.DefTarget target = pass.analysis.defTarget(def);
            return target != null && target.kind() == ScopeAnalysis.DefKind.DEFAULT;
        }
        return false;
    }

    private boolean isExportOrInterfaceMethod(BabbleParser.FunctionLiteralContext fn) {
        if (isInterfaceDefault(fn)) {
            return true;
        }
        ParserRuleContext cur = fn;
        while ((cur.getParent() instanceof BabbleParser.BlockExpressionContext blk
                && blk.block().expression().size() == 1)
                || cur.getParent() instanceof BabbleParser.OverloadExpressionContext) {
            cur = cur.getParent();
        }
        return cur.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == cur
                && checker.isExportedDef(def);
    }

    ParsedPrefix parseFunctionPrefix(BabbleParser.FunctionLiteralContext ctx, boolean reportErrors) {
        if (!reportErrors) {
            ParsedPrefix cached = fnPrefixes.get(ctx);
            if (cached != null) {
                return cached;
            }
        }
        boolean untyped = isCompletelyUntyped(ctx);
        boolean hasThis = canReferenceThis(checker.scopeOf(ctx));
        boolean hasMutParam = false;
        boolean hasAnyParamMod = false;
        for (BabbleParser.FnParamContext param : ctx.fnParameters().fnParam()) {
            ParamMods pm = parseParamMods(param, reportErrors);
            hasMutParam |= pm.mut();
            hasAnyParamMod |= pm.mut() || pm.shared() || pm.unique();
        }
        if (untyped) {
            if (checker.inFile(ctx)) {
                fnPrefixes.put(ctx, ParsedPrefix.EMPTY);
            }
            return ParsedPrefix.EMPTY;
        }
        ParsedPrefix parsed = parseWords(
                ctx.effectPrefix() != null ? effectPrefixTokens(ctx.effectPrefix()) : List.of(),
                ParseContext.FUNCTION_LITERAL, hasThis, hasMutParam, hasAnyParamMod,
                ctx.uniqueReturn != null, ctx, reportErrors);
        if (reportErrors) {
            if (parsed.axiom() && !pass.hostEffects.allowAxioms()) {
                checker.effectDiagnostic("axiom declarations are disabled on this engine", ctx);
            }
        }
        if (checker.inFile(ctx)) {
            fnPrefixes.put(ctx, parsed);
        }
        return parsed;
    }

    private static List<Token> effectPrefixTokens(BabbleParser.EffectPrefixContext prefix) {
        return prefix.effectWord().stream().map(ParserRuleContext::getStart).toList();
    }

    private static List<Token> effectClauseTokens(BabbleParser.EffectClauseContext clause) {
        List<Token> words = new ArrayList<>();
        for (int i = 0; i < clause.getChildCount(); i++) {
            ParseTree child = clause.getChild(i);
            if (child instanceof ParserRuleContext prc) {
                words.add(prc.getStart());
            } else if (child instanceof org.antlr.v4.runtime.tree.TerminalNode tn) {
                words.add(tn.getSymbol());
            }
        }
        return words;
    }

    ParsedPrefix parseFunctionTypePrefix(BabbleParser.FunctionTypeContext ctx, boolean reportErrors) {
        return parseFunctionTypePrefix(ctx, false, reportErrors);
    }

    ParsedPrefix parseFunctionTypePrefix(BabbleParser.FunctionTypeContext ctx, boolean interfaceMethod, boolean reportErrors) {
        boolean report = reportErrors && validatedTypeTrees.add(ctx);
        List<Token> words = ctx.effectPrefix() != null ? effectPrefixTokens(ctx.effectPrefix()) : List.of();
        boolean hasMutParam = false;
        boolean hasAnyParamMod = false;
        for (BabbleParser.FnTypeParamContext param : ctx.fnTypeParameters().fnTypeParam()) {
            ParamMods pm = parseParamMods(param, report);
            hasMutParam |= pm.mut();
            hasAnyParamMod |= pm.mut() || pm.shared() || pm.unique();
        }
        return parseWords(words,
                interfaceMethod ? ParseContext.INTERFACE_METHOD : ParseContext.FUNCTION_TYPE,
                interfaceMethod, hasMutParam, hasAnyParamMod, ctx.uniqueReturn != null, ctx, report);
    }

    ParamMods parseParamMods(ParserRuleContext param, boolean reportErrors) {
        if (!reportErrors) {
            ParamMods cached = paramModsMap.get(param);
            if (cached != null) {
                return cached;
            }
        }
        boolean mut = false;
        boolean shared = false;
        boolean unique = false;
        for (BabbleParser.ParamModifierContext modCtx : ScopeAnalysis.paramModifiers(param)) {
            Token tok = modCtx.getStart();
            switch (tok.getText()) {
                case "mut" -> {
                    if (mut && reportErrors) {
                        checker.effectDiagnostic("Duplicate parameter modifier : mut", modCtx);
                    }
                    mut = true;
                }
                case "shared" -> {
                    if (shared && reportErrors) {
                        checker.effectDiagnostic("Duplicate parameter modifier : shared", modCtx);
                    }
                    shared = true;
                }
                case "unique" -> {
                    if (unique && reportErrors) {
                        checker.effectDiagnostic("Duplicate parameter modifier : unique", modCtx);
                    }
                    unique = true;
                }
                default -> {
                    if (reportErrors) {
                        checker.effectDiagnostic("Unknown parameter modifier : " + tok.getText(), modCtx);
                    }
                }
            }
        }
        if (reportErrors) {
            if (unique && shared) {
                checker.effectDiagnostic("unique and shared are mutually exclusive", param);
            }
            if (unique && mut) {
                checker.effectDiagnostic("unique and mut are mutually exclusive : consuming unique parameters already allow private mutation", param);
            }
        }
        if (unique) {
            shared = false;
            mut = false;
        }
        ParamMods mods = (mut || shared || unique) ? new ParamMods(mut, shared, unique) : ParamMods.NONE;
        if (checker.inFile(param)) {
            paramModsMap.put(param, mods);
        }
        return mods;
    }

    private ParsedPrefix parseWords(List<Token> tokens,
                                    ParseContext kind,
                                    boolean hasThis,
                                    boolean hasMutParam,
                                    boolean hasAnyParamMod,
                                    boolean hasUniqueReturnType,
                                    ParserRuleContext owner,
                                    boolean reportErrors) {
        boolean inFunctionType = kind == ParseContext.FUNCTION_TYPE || kind == ParseContext.INTERFACE_METHOD;
        boolean isJavaTarget = kind == ParseContext.JAVA_TARGET;
        EnumSet<Effect> explicitEffects = EnumSet.noneOf(Effect.class);
        boolean explicitPure = false;
        boolean explicitVolatile = false;
        boolean io = false;
        boolean mutThis = false;
        boolean explicitSharedThis = false;
        boolean uniqueReturn = hasUniqueReturnType;
        boolean axiom = false;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token tok = tokens.get(i);
            String word = tok.getText();
            if (!seen.add(word) && reportErrors) {
                checker.effectDiagnostic("Duplicate effect modifier : " + word, owner);
            }
            switch (word) {
                case "axiom" -> {
                    if (inFunctionType) {
                        if (reportErrors) {
                            checker.effectDiagnostic("axiom is not allowed in a function type", owner);
                        }
                    } else if (i > 0 && reportErrors) {
                        checker.effectDiagnostic("axiom must appear first before effect modifiers", owner);
                    }
                    axiom = true;
                }
                case "pure" -> explicitPure = true;
                case "volatile" -> explicitVolatile = true;
                case "io" -> io = true;
                case "nothrow" -> explicitEffects.add(Effect.NOTHROW);
                case "idempotent" -> explicitEffects.add(Effect.IDEMPOTENT);
                case "commutative" -> explicitEffects.add(Effect.COMMUTATIVE);
                case "associative" -> explicitEffects.add(Effect.ASSOCIATIVE);
                case "monotone" -> explicitEffects.add(Effect.MONOTONE);
                case "injective" -> explicitEffects.add(Effect.INJECTIVE);
                case "unique" -> uniqueReturn = true;
                case "mut" -> {
                    if (!hasThis && kind == ParseContext.FUNCTION_LITERAL && reportErrors) {
                        checker.effectDiagnostic("mut on a function requires a receiver (this) : mark mutated parameters with mut", owner);
                    }
                    mutThis = true;
                }
                case "shared" -> {
                    if (!hasThis && kind == ParseContext.FUNCTION_LITERAL && reportErrors) {
                        checker.effectDiagnostic("shared on a function requires a receiver (this) : mark escaping parameters with shared", owner);
                    }
                    explicitSharedThis = true;
                }
                default -> {
                    if (reportErrors) {
                        checker.effectDiagnostic("Unknown effect modifier : " + word, owner);
                    }
                }
            }
        }
        if (reportErrors) {
            if (explicitPure && !axiom) {
                checker.effectDiagnostic("pure is the default on functions : only use pure with axiom", owner);
            }
            if (axiom && io) {
                checker.effectDiagnostic("axiom and io cannot be combined", owner);
            } else if (isJavaTarget && io && (explicitPure || explicitVolatile || !explicitEffects.isEmpty() || mutThis || explicitSharedThis || uniqueReturn)) {
                checker.effectDiagnostic("io cannot be combined with effect modifiers on a Java target", owner);
            }
            if (io && explicitVolatile) {
                checker.effectDiagnostic("io already implies volatile : use io alone", owner);
            }
            if (io && explicitPure) {
                checker.effectDiagnostic("io and pure cannot be combined", owner);
            }
            if (explicitPure && explicitVolatile) {
                checker.effectDiagnostic("pure and volatile cannot be combined", owner);
            }
            if (explicitPure && (mutThis || hasMutParam)) {
                checker.effectDiagnostic("pure and mut are mutually exclusive : use mut (or volatile mut)", owner);
            }
            if (axiom && !explicitPure && !explicitVolatile && explicitEffects.isEmpty()
                    && !mutThis && !explicitSharedThis && !uniqueReturn && !hasAnyParamMod) {
                checker.effectDiagnostic("axiom requires at least one effect modifier", owner);
            }
        }

        EnumSet<Effect> effects = EnumSet.copyOf(explicitEffects);
        boolean anyMut = mutThis || hasMutParam;
        boolean hasExplicitWords = !tokens.isEmpty() || hasUniqueReturnType;
        if (isJavaTarget) {
            if (io) {
                return new ParsedPrefix(EnumSet.noneOf(Effect.class), false, false, false, false, false, true, false, true);
            }
            if (explicitPure && !anyMut) {
                effects.add(Effect.PURE);
                effects.add(Effect.HERMETIC);
                effects.add(Effect.DETERMINISTIC);
            } else if (explicitVolatile) {
                effects.add(Effect.HERMETIC);
                if (!anyMut) {
                    effects.add(Effect.PURE);
                }
            } else if (mutThis) {
                effects.add(Effect.HERMETIC);
                effects.add(Effect.DETERMINISTIC);
            }
            boolean borrowThis = hasThis && !explicitSharedThis;
            return new ParsedPrefix(effects, mutThis, borrowThis, explicitSharedThis, uniqueReturn, axiom, false, explicitVolatile, true);
        }

        if (axiom) {
            if (explicitPure && !anyMut) {
                effects.add(Effect.PURE);
                effects.add(Effect.HERMETIC);
                effects.add(Effect.DETERMINISTIC);
            } else if (explicitVolatile) {
                effects.add(Effect.HERMETIC);
                if (!anyMut) {
                    effects.add(Effect.PURE);
                }
            } else if (anyMut) {
                effects.add(Effect.HERMETIC);
                effects.add(Effect.DETERMINISTIC);
            }
            boolean borrowThis = !explicitSharedThis;
            return new ParsedPrefix(effects, mutThis, borrowThis, explicitSharedThis, uniqueReturn, true, false, explicitVolatile, true);
        }

        // Typed function literal, function type, or interface method: pure & deterministic & @BORROWED by default (BIP016-D3, D4)!
        if (io) {
            effects.remove(Effect.PURE);
            effects.remove(Effect.HERMETIC);
            effects.remove(Effect.DETERMINISTIC);
        } else if (explicitVolatile) {
            effects.add(Effect.HERMETIC);
            effects.remove(Effect.DETERMINISTIC);
            if (!anyMut) {
                effects.add(Effect.PURE);
            }
        } else {
            effects.add(Effect.HERMETIC);
            effects.add(Effect.DETERMINISTIC);
            if (!anyMut) {
                effects.add(Effect.PURE);
            }
        }
        boolean borrowThis = !explicitSharedThis;
        return new ParsedPrefix(effects, mutThis, borrowThis, explicitSharedThis, uniqueReturn,
                false, io, !io && explicitVolatile, hasExplicitWords);
    }

    private static boolean canReferenceThis(ScopeNode scope) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            if (s.kind == ScopeNode.Kind.FUNCTION && s.memberOf != null) {
                return !s.staticMethod;
            }
            if (s.kind == ScopeNode.Kind.CLASS_BODY || s.kind == ScopeNode.Kind.INTERFACE_BODY) {
                return true;
            }
            if (s.kind == ScopeNode.Kind.STATIC_BODY) {
                return false;
            }
        }
        return false;
    }

    // ================================================================ 2. Pre-Pass & Inference Driver

    void inferAll(BabbleParser.FileContext file) {
        collectDeclarations(file);
        classifyClosures(file);

        // Collect all callables in file: FunctionLiteralContext and ClassExpressionContext constructors.
        List<ParserRuleContext> callables = new ArrayList<>();
        collectCallables(file, callables);

        // Initialize summaries.
        for (ParserRuleContext callable : callables) {
            summaries.put(callable, initialSummary(callable));
        }

        // Build call graph among intra-file callables and condense into SCCs (Tarjan).
        Map<ParserRuleContext, Set<ParserRuleContext>> edges = buildCallGraph(callables);
        List<List<ParserRuleContext>> sccs = tarjanScc(callables, edges);

        // Tarjan emits SCCs in reverse topological order (callees before callers)!
        for (List<ParserRuleContext> scc : sccs) {
            boolean cyclic = scc.size() > 1
                    || (scc.size() == 1 && edges.getOrDefault(scc.get(0), Set.of()).contains(scc.get(0)));
            if (cyclic) {
                cyclicCallables.addAll(scc);
            }
            int iter = 0;
            boolean changed;
            do {
                changed = false;
                for (ParserRuleContext callable : scc) {
                    if (isFixedSummary(callable)) {
                        continue;
                    }
                    specializationCache.clear();
                    CallableSummary prev = summaries.get(callable);
                    CallableSummary next = recomputeSummary(callable, prev, null, false);
                    if (!next.equals(prev)) {
                        summaries.put(callable, next);
                        changed = true;
                    }
                }
            } while (changed && ++iter < 32);
        }

        // Finalize PURE (= HERMETIC && no mutThis && no mutParams) & algebraic composition,
        // and build caller-visible contracts (BIP007-D2).
        for (ParserRuleContext callable : callables) {
            CallableSummary internal = finalizeSummary(callable, summaries.get(callable));
            summaries.put(callable, internal);
            CallableSummary visible = buildVisibleSummary(callable, internal);
            visibleSummaries.put(callable, visible);
            pass.analysis.setCallableEffects(callable, visible.effects());
            pass.analysis.setCallableType(callable, checker.rawTypeOf(callable));
        }
    }

    /** Whether a {@code while} loop (its test and body) cannot throw, as last walked ; false if never walked. */
    boolean loopNothrow(BabbleParser.WhileExpressionContext loop) {
        return loopNothrow.getOrDefault(loop, false);
    }

    /** Whether a callable cannot throw, as inferred from its body (not its declared contract). */
    boolean inferredNothrow(ParserRuleContext callable) {
        CallableSummary summary = summaries.get(callable);
        return summary != null && summary.effects().isNothrow();
    }

    CallableSummary visibleSummary(BabbleParser.FunctionLiteralContext fn) {
        return visibleSummaries.get(fn);
    }

    /**
     * Whether a callable that is not a Babble function — print, a Java method, or a class
     * constructor — satisfies the effects of {@code target} : its effects, and per parameter no
     * mutation the target doesn't allow and no retention of a parameter the target only borrows.
     */
    boolean callableSatisfies(Type from, Type.Function target) {
        EffectSet effects;
        boolean[] mutParams;
        boolean[] borrowParams;
        boolean borrowRest;
        if (from instanceof Type.JavaMethod jm) {
            Method exact = JavaTypeBridge.uniqueArityMethod(jm.owner(), jm.name(), target.parameters().size());
            JavaCallProfile profile = resolveJavaMethodProfile(jm.owner(), jm.name(), exact);
            effects = profile.effects();
            mutParams = profile.mutParams();
            borrowParams = profile.borrowParams();
            borrowRest = profile.borrowReceiver();
            // A Java method value (`Math.abs`, `sb.length`, `xs.add`) is static or already bound to its receiver:
            // it has no `this` parameter at the call site, so it trivially satisfies `borrowThis`, and when assigned
            // to an `io` (or unconstrained) target its bound receiver mutation is covered by `io`.
            boolean keepMutThis = effects.mutThis() && target.effects().hasExplicitAnnotations() && !target.effects().isIO();
            if (!effects.borrowThis() || (effects.mutThis() && !keepMutThis)) {
                effects = new EffectSet(effects.effects(), effects.declared(), keepMutThis, true, false,
                        effects.uniqueResult(), effects.explicitUniqueResult(), effects.terminating(),
                        effects.isIO(), effects.explicitVolatile(), effects.axiomatic());
            }
        } else if (from instanceof Type.ClassValue cv && !cv.node().isInterface()) {
            CallableSummary summary = constructorSummary(cv.node());
            effects = summary != null ? summary.effects() : EffectSet.EMPTY;
            mutParams = summary != null ? summary.mutParams() : new boolean[0];
            borrowParams = summary != null ? summary.borrowParams() : new boolean[0];
            borrowRest = false;
        } else {
            return true;
        }
        if (!effects.satisfies(target.effects())) {
            return false;
        }
        for (int i = 0; i < target.parameters().size(); i++) {
            Type.Parameter tp = target.parameters().get(i);
            boolean mut = i < mutParams.length && mutParams[i];
            boolean borrow = i < borrowParams.length ? borrowParams[i] : borrowRest;
            if (mut && !tp.mut() || (tp.borrow() && !borrow && !isDeeplyImmutable(tp.type()))) {
                return false;
            }
        }
        return true;
    }

    CallableSummary constructorSummary(TypeNode node) {
        if (node.ctx instanceof BabbleParser.ClassExpressionContext cls) {
            CallableSummary local = visibleSummaries.get(cls);
            if (local != null) {
                return local;
            }
            CallableSummary internal = summaries.get(cls);
            if (internal != null) {
                return internal;
            }
        }
        return checker.remoteConstructorSummary(node);
    }

    EffectSet defOverrideEffects(BabbleParser.DefExpressionContext def) {
        ParsedPrefix prefix = defPrefixes.get(def);
        return prefix != null ? prefix.toEffectSet() : null;
    }

    // ================================================================ 3. Pre-pass : Axiom & IO Collection

    private void collectAxiomExpressions() {
        if (pass.axiomExpressions.isEmpty()) {
            return;
        }
        AxiomData.Builder localBuilder = new AxiomData.Builder();
        AxiomData.Builder committedBuilder = new AxiomData.Builder();
        AxiomData.Builder exportedBuilder = new AxiomData.Builder();
        AxiomData.ErrorSink sink = (msg, at) -> pendingAxiomDiags.add(new PendingAxiomDiag(msg, at));

        for (BlockScopes.PendingAxiomExpr pending : pass.axiomExpressions) {
            BabbleParser.AxiomExpressionContext ctx = pending.ctx();
            ScopeNode scope = pending.scope();
            if (!pass.hostEffects.allowAxioms()) {
                sink.report("axiom declarations are disabled on this engine", ctx);
                continue;
            }
            AxiomData.ClassResolver resolver = path -> resolveAxiomClassPath(path, scope);
            AxiomData.ParsedAxiom parsed = AxiomData.parse(ctx, resolver, false, sink);
            if (parsed == null || !parsed.isJava()) {
                continue;
            }
            if (parsed.classAxiom() != null) {
                if (localBuilder.hasClassAxiom(parsed.classAxiom().targetClass())) {
                    sink.report("Duplicate axiom for class : " + parsed.classAxiom().targetClass().getName(), ctx);
                    continue;
                }
                boolean conflict = false;
                AxiomData.Builder selfCheck = new AxiomData.Builder();
                for (AxiomData.MemberAxiom m : parsed.classAxiom().members()) {
                    if (localBuilder.hasMemberConflict(m) || selfCheck.hasMemberConflict(m)) {
                        sink.report("Duplicate axiom for member : " + m.ownerClass().getName() + "." + m.name(), ctx);
                        conflict = true;
                    } else {
                        selfCheck.addMemberAxiom(m);
                    }
                }
                if (conflict) {
                    continue;
                }
                localBuilder.addClassAxiom(parsed.classAxiom());
                if (scope.kind == ScopeNode.Kind.FILE) {
                    committedBuilder.addClassAxiom(parsed.classAxiom());
                }
                if (ctx.EXPORT() != null) {
                    exportedBuilder.addClassAxiom(parsed.classAxiom());
                }
            } else if (parsed.memberAxiom() != null) {
                AxiomData.MemberAxiom m = parsed.memberAxiom();
                if (localBuilder.hasMemberConflict(m)) {
                    sink.report("Duplicate axiom for member : " + m.ownerClass().getName() + "." + m.name(), ctx);
                    continue;
                }
                localBuilder.addMemberAxiom(m);
                if (scope.kind == ScopeNode.Kind.FILE) {
                    committedBuilder.addMemberAxiom(m);
                }
                if (ctx.EXPORT() != null) {
                    exportedBuilder.addMemberAxiom(m);
                }
            }
        }
        AxiomData.AxiomTable fileTable = localBuilder.build();
        if (!fileTable.isEmpty()) {
            localAxioms = localAxioms.mergeOver(fileTable);
        }
        committedAxioms = committedBuilder.build();
        exportedAxioms = exportedBuilder.build();
    }

    private static Class<?> resolveAxiomClassPath(List<String> path, ScopeNode scope) {
        Class<?> direct = JavaTypeBridge.loadClass(String.join(".", path));
        if (direct != null) {
            return direct;
        }
        Object resolved = TypeChecker.resolveName(null, path.get(0), scope);
        if (resolved instanceof BabbleParser.DefExpressionContext def && def.value != null) {
            String pkg = importedJavaPackageName(def.value);
            if (pkg != null) {
                String joined = path.size() == 1 ? pkg : pkg + "." + String.join(".", path.subList(1, path.size()));
                return JavaTypeBridge.loadClass(joined);
            }
            List<String> selectorPath = staticSelectorPath(def.value);
            if (selectorPath != null) {
                List<String> full = new ArrayList<>(selectorPath);
                full.addAll(path.subList(1, path.size()));
                return JavaTypeBridge.loadClass(String.join(".", full));
            }
        }
        return null;
    }

    private static String importedJavaPackageName(BabbleParser.ExpressionContext expr) {
        if (expr instanceof BabbleParser.CallContext call
                && call.expression() instanceof BabbleParser.SelectorContext sel && sel.expression() == null
                && ("import".equals(sel.ID().getText()) || "importe".equals(sel.ID().getText()))
                && call.callParameters().callParameter().size() == 1
                && call.callParameters().callParameter(0).expression() instanceof BabbleParser.StringContext str) {
            String name = org.babblelang.parser.StringLiterals.decode(str);
            return name.endsWith(".ba") ? null : name;
        }
        return null;
    }

    private static List<String> staticSelectorPath(BabbleParser.ExpressionContext expr) {
        if (expr instanceof BabbleParser.SelectorContext sel) {
            if (sel.expression() == null) {
                return List.of(sel.ID().getText());
            }
            List<String> prefix = staticSelectorPath(sel.expression());
            if (prefix == null) {
                return null;
            }
            List<String> out = new ArrayList<>(prefix);
            out.add(sel.memberName().getText());
            return out;
        }
        return null;
    }

    private void collectDeclarations(BabbleParser.FileContext file) {
        // 0. Report any diagnostics discovered while parsing BIP019 axiomExpressions.
        for (PendingAxiomDiag diag : pendingAxiomDiags) {
            checker.effectDiagnostic(diag.message(), diag.at());
        }

        // 1. Standalone effectDeclarations recorded by BlockScopes.
        for (BlockScopes.PendingEffectDecl pending : pass.effectDeclarations) {
            BabbleParser.EffectDeclarationContext ctx = pending.ctx();
            ScopeNode scope = pending.scope();
            List<Token> words = effectClauseTokens(ctx.effectClause());
            boolean isCtor = ctx.ctorOpen != null;
            ParsedPrefix prefix = parseTargetPrefix(words, isCtor, ctx);
            if (!pass.hostEffects.allowAxioms()) {
                checker.effectDiagnostic((prefix.io() ? "io" : "axiom") + " declarations are disabled on this engine", ctx);
                continue;
            }
            checkDeclarationPlacement(scope, prefix.io() ? "io" : "axiom", ctx);
            HostEffects.JavaTarget target = resolveEffectTarget(ctx.effectTarget(), isCtor, scope, ctx);
            if (target == null) {
                continue;
            }
            validateJavaTargetPrefix(target, prefix, ctx);
            HostEffects.OverrideEntry entry = toOverrideEntry(prefix);
            localJavaEffects.put(target, entry);
            if (scope.kind == ScopeNode.Kind.FILE) {
                committedJavaEffects.put(target, entry);
            }
            if (ctx.EXPORT() != null) {
                exportedJavaEffects.put(target, entry);
            }
        }

        // 2. DefExpressions with effectClause (e.g. def mkdirs = axiom idempotent on Files.createDirectories).
        collectDefAxioms(file);
    }

    private void collectDefAxioms(ParseTree tree) {
        if (tree instanceof BabbleParser.DefExpressionContext def && def.effectClause() != null) {
            List<Token> words = effectClauseTokens(def.effectClause());
            ParsedPrefix prefix = parseTargetPrefix(words, false, def);
            defPrefixes.put(def, prefix);
            ScopeNode scope = pass.definitionScopes.get(def);
            if (!pass.hostEffects.allowAxioms()) {
                checker.effectDiagnostic((prefix.io() ? "io" : "axiom") + " declarations are disabled on this engine", def);
            } else {
                if (scope != null) {
                    checkDeclarationPlacement(scope, prefix.io() ? "io" : "axiom", def);
                }
                Type valType = def.value != null ? checker.rawTypeOf(def.value) : null;
                HostEffects.JavaTarget target = null;
                if (valType instanceof Type.JavaMethod jm) {
                    target = HostEffects.JavaTarget.ofMethod(jm.owner(), jm.name());
                } else if (valType instanceof Type.JavaClassValue jcv) {
                    target = HostEffects.JavaTarget.ofClass(jcv.type());
                } else if (valType != null && !(valType instanceof Type.Function) && valType != Type.ERROR) {
                    checker.effectDiagnostic("Invalid " + (prefix.io() ? "io" : "axiom")
                            + " target : expected a Java class, Java method, or function", def);
                }
                if (target != null) {
                    validateJavaTargetPrefix(target, prefix, def);
                    HostEffects.OverrideEntry entry = toOverrideEntry(prefix);
                    localJavaEffects.put(target, entry);
                    if (scope != null && scope.kind == ScopeNode.Kind.FILE) {
                        committedJavaEffects.put(target, entry);
                    }
                    if (checker.isExportedDef(def)) {
                        exportedJavaEffects.put(target, entry);
                    }
                }
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectDefAxioms(tree.getChild(i));
        }
    }

    private ParsedPrefix parseTargetPrefix(List<Token> words, boolean isCtor, ParserRuleContext ctx) {
        return parseWords(words, ParseContext.JAVA_TARGET, !isCtor, false, false, false, ctx, true);
    }

    private void checkDeclarationPlacement(ScopeNode scope, String keyword, ParserRuleContext ctx) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            if (s.kind == ScopeNode.Kind.FUNCTION && s.owner instanceof BabbleParser.FunctionLiteralContext fn) {
                ParsedPrefix p = parseFunctionPrefix(fn, false);
                if (!p.axiom() && !p.io() && (p.effects().contains(Effect.PURE)
                        || p.effects().contains(Effect.HERMETIC)
                        || p.effects().contains(Effect.DETERMINISTIC))) {
                    checker.effectDiagnostic(keyword + " declaration is not allowed inside a verified constrained function", ctx);
                    return;
                }
            }
        }
    }

    private HostEffects.JavaTarget resolveEffectTarget(BabbleParser.EffectTargetContext targetCtx,
                                                       boolean isCtor,
                                                       ScopeNode scope,
                                                       ParserRuleContext reportAt) {
        String first = ScopeAnalysis.effectTargetRoot(targetCtx);
        List<String> members = ScopeAnalysis.effectTargetMembers(targetCtx);
        Type current;
        if (targetCtx.STRING() != null) {
            if (!"import".equals(first) && !"importe".equals(first)) {
                checker.effectDiagnostic("Invalid target : expected import(\"...\") or Java class path", reportAt);
                return null;
            }
            if (TypeChecks.resolveName(first, scope) != null) {
                checker.effectDiagnostic("Invalid target : shadowed " + first, reportAt);
                return null;
            }
            String pkg = org.babblelang.parser.StringLiterals.decode(targetCtx.STRING().getSymbol());
            if (pkg.endsWith(".ba")) {
                checker.effectDiagnostic("Java effect target cannot be a .ba module : " + pkg, reportAt);
                return null;
            }
            Class<?> directClass = checker.findJavaClass(pkg);
            current = directClass != null ? new Type.JavaClassValue(directClass) : new Type.JavaPackage(pkg);
        } else {
            Object resolved = checker.resolveNameForEffects(first, scope);
            if (resolved instanceof ParserRuleContext decl) {
                current = checker.typeOfDeclarationForEffects(decl);
            } else {
                current = checker.implicitTypeForEffects(first);
                if (current == null) {
                    checker.effectDiagnostic("Unknown identifier in effect declaration : " + first, reportAt);
                    return null;
                }
            }
        }

        for (int idx = 0; idx < members.size(); idx++) {
            String step = members.get(idx);
            boolean last = (idx == members.size() - 1);
            if (current instanceof Type.JavaPackage jp) {
                String qualified = jp.name() + "." + step;
                Class<?> cls = checker.findJavaClass(qualified);
                current = cls != null ? new Type.JavaClassValue(cls) : new Type.JavaPackage(qualified);
            } else if (current instanceof Type.JavaClassValue jcv) {
                Class<?> memberCls = checker.findMemberJavaClass(jcv.type(), step);
                if (memberCls != null && (!last || isCtor)) {
                    current = new Type.JavaClassValue(memberCls);
                } else if (last && !isCtor) {
                    if (!HostEffects.hasMethodNamed(jcv.type(), step)) {
                        if (memberCls != null) {
                            return HostEffects.JavaTarget.ofClass(memberCls);
                        }
                        checker.effectDiagnostic("No such Java method : " + step + " on " + jcv.type().getName(), reportAt);
                        return null;
                    }
                    return HostEffects.JavaTarget.ofMethod(jcv.type(), step);
                } else {
                    checker.effectDiagnostic("Unknown Java member class : " + step + " on " + jcv.type().getName(), reportAt);
                    return null;
                }
            } else if (current instanceof Type.Module mod) {
                current = checker.moduleMemberTypeForEffects(mod.module(), step);
                if (current == null) {
                    checker.effectDiagnostic("Unknown export : " + step + " in " + mod.module().name(), reportAt);
                    return null;
                }
            } else {
                checker.effectDiagnostic("Invalid effect declaration target : " + targetCtx.getText(), reportAt);
                return null;
            }
        }

        if (current instanceof Type.JavaClassValue jcv) {
            return isCtor
                    ? HostEffects.JavaTarget.ofConstructor(jcv.type())
                    : HostEffects.JavaTarget.ofClass(jcv.type());
        }
        if (current instanceof Type.JavaMethod jm && !isCtor) {
            return HostEffects.JavaTarget.ofMethod(jm.owner(), jm.name());
        }
        checker.effectDiagnostic("Invalid effect declaration target : expected a Java class, constructor, or method", reportAt);
        return null;
    }

    private void validateJavaTargetPrefix(HostEffects.JavaTarget target, ParsedPrefix prefix, ParserRuleContext at) {
        if (prefix.io()) {
            return;
        }
        if (target.kind() == HostEffects.TargetKind.CLASS && prefix.mutThis()) {
            checker.effectDiagnostic("Bare class axiom cannot declare mut : declare mut on specific methods instead", at);
        }
        if (target.kind() == HostEffects.TargetKind.CONSTRUCTOR && prefix.mutThis()) {
            checker.effectDiagnostic("Constructor axiom cannot declare mut", at);
        }
    }

    private static HostEffects.OverrideEntry toOverrideEntry(ParsedPrefix prefix) {
        if (prefix.io()) {
            return HostEffects.OverrideEntry.ioEntry();
        }
        return new HostEffects.OverrideEntry(prefix.effects(), prefix.mutThis(), !prefix.explicitSharedThis(),
                prefix.uniqueReturn(), false, prefix.explicitVolatile());
    }

    // ================================================================ 4. 7-Tier Java Effect Resolution

    JavaCallProfile resolveJavaMethodProfile(Class<?> owner, String methodName, Method exactMethod) {
        // Tier 1: File / module / session method override (BIP019 structured axioms first, then legacy effectDeclarations).
        if (!localAxioms.isEmpty()) {
            AxiomData.MemberAxiom localAx = AxiomData.lookupMethod(owner, methodName, exactMethod, localAxioms::directMembers);
            if (localAx != null) {
                return localAx.toProfile(exactMethod);
            }
        }
        HostEffects.OverrideEntry localMethod = lookupHierarchyMethod(localJavaEffects, owner, methodName);
        if (localMethod != null) {
            return profileFromOverride(localMethod, exactMethod, localMethod.effects().contains(Effect.NOTHROW));
        }
        // Tier 2: Engine method override.
        HostEffects.OverrideEntry hostMethod = lookupHierarchyMethod(pass.hostEffects.overrides(), owner, methodName);
        if (hostMethod != null) {
            return profileFromOverride(hostMethod, exactMethod, hostMethod.effects().contains(Effect.NOTHROW));
        }
        // Tier 3: Java method annotations (@Pure, @Hermetic, @Mut, ...).
        if (exactMethod != null && hasAnyEffectAnnotation(exactMethod)) {
            return profileFromAnnotations(exactMethod, exactMethod.getDeclaringClass(), false);
        }
        // Tier 4: File / module / session or engine bare-class override.
        Class<?> declOwner = exactMethod != null ? exactMethod.getDeclaringClass() : owner;
        HostEffects.OverrideEntry localClass = localJavaEffects.get(HostEffects.JavaTarget.ofClass(owner));
        if (localClass == null && declOwner != owner) {
            localClass = localJavaEffects.get(HostEffects.JavaTarget.ofClass(declOwner));
        }
        if (localClass != null) {
            return profileFromOverride(localClass, exactMethod, localClass.effects().contains(Effect.NOTHROW));
        }
        HostEffects.OverrideEntry hostClass = pass.hostEffects.lookup(HostEffects.JavaTarget.ofClass(owner));
        if (hostClass == null && declOwner != owner) {
            hostClass = pass.hostEffects.lookup(HostEffects.JavaTarget.ofClass(declOwner));
        }
        if (hostClass != null) {
            return profileFromOverride(hostClass, exactMethod, hostClass.effects().contains(Effect.NOTHROW));
        }
        // Tier 5: Java class-level annotations on owner / declaring class.
        if (hasClassEffectAnnotation(owner)) {
            return profileFromAnnotations(exactMethod, owner, true);
        }
        if (declOwner != owner && hasClassEffectAnnotation(declOwner)) {
            return profileFromAnnotations(exactMethod, declOwner, true);
        }
        // Tier 6: Curated axioms (BIP019).
        AxiomData.MemberAxiom curatedAx = pass.hostEffects.builtinAxioms().methodAxiom(owner, methodName, exactMethod);
        if (curatedAx != null) {
            if (exactMethod != null && Boolean.getBoolean("babble.axioms.report")) {
                reportRedundantCuratedAxiom(exactMethod, curatedAx);
            }
            return curatedAx.toProfile(exactMethod);
        }
        // Tier 7: ASM bytecode static analysis (on by default, BIP019 §5).
        if (exactMethod != null && pass.hostEffects.bytecodeEffectAnalysis()) {
            BytecodeEffectAnalyzer.Result analyzed = BytecodeEffectAnalyzer.analyze(exactMethod, pass.hostEffects.builtinAxioms());
            if (!analyzed.effects().isUnconstrained()) {
                return analyzed.toProfile(exactMethod);
            }
        }
        // Tier 8: Unconstrained default.
        int paramCount = exactMethod != null ? exactMethod.getParameterCount() : 0;
        return new JavaCallProfile(EffectSet.EMPTY, false, false, new boolean[paramCount], new boolean[paramCount], false);
    }

    JavaCallProfile resolveJavaConstructorProfile(Class<?> owner, Constructor<?> exactCtor) {
        // Tier 1: File / module / session constructor override (BIP019 structured axioms first, then legacy).
        if (!localAxioms.isEmpty()) {
            AxiomData.MemberAxiom localAx = AxiomData.lookupConstructor(owner, exactCtor, localAxioms::directMembers);
            if (localAx != null) {
                return localAx.toProfile(exactCtor);
            }
        }
        HostEffects.OverrideEntry localCtor = localJavaEffects.get(HostEffects.JavaTarget.ofConstructor(owner));
        if (localCtor != null) {
            return profileFromOverride(localCtor, exactCtor, localCtor.effects().contains(Effect.NOTHROW));
        }
        // Tier 2: Engine constructor override.
        HostEffects.OverrideEntry hostCtor = pass.hostEffects.lookup(HostEffects.JavaTarget.ofConstructor(owner));
        if (hostCtor != null) {
            return profileFromOverride(hostCtor, exactCtor, hostCtor.effects().contains(Effect.NOTHROW));
        }
        // Tier 3: Java constructor annotations.
        if (exactCtor != null && hasAnyEffectAnnotation(exactCtor)) {
            return profileFromAnnotations(exactCtor, owner, false);
        }
        // Tier 4: Bare-class override.
        HostEffects.OverrideEntry localClass = localJavaEffects.get(HostEffects.JavaTarget.ofClass(owner));
        if (localClass != null) {
            return profileFromOverride(localClass, exactCtor, localClass.effects().contains(Effect.NOTHROW));
        }
        HostEffects.OverrideEntry hostClass = pass.hostEffects.lookup(HostEffects.JavaTarget.ofClass(owner));
        if (hostClass != null) {
            return profileFromOverride(hostClass, exactCtor, hostClass.effects().contains(Effect.NOTHROW));
        }
        // Tier 5: Java class-level annotations.
        if (hasClassEffectAnnotation(owner)) {
            return profileFromAnnotations(exactCtor, owner, true);
        }
        // Tier 6: Curated axioms (BIP019).
        AxiomData.MemberAxiom curatedAx = pass.hostEffects.builtinAxioms().constructorAxiom(owner, exactCtor);
        if (curatedAx != null) {
            if (exactCtor != null && Boolean.getBoolean("babble.axioms.report")) {
                reportRedundantCuratedAxiom(exactCtor, curatedAx);
            }
            return curatedAx.toProfile(exactCtor);
        }
        int paramCount = exactCtor != null ? exactCtor.getParameterCount() : 0;
        // Tier 7: ASM bytecode static analysis of constructors (on by default, BIP019 §5).
        if (exactCtor != null && pass.hostEffects.bytecodeEffectAnalysis()) {
            BytecodeEffectAnalyzer.Result analyzed = BytecodeEffectAnalyzer.analyze(exactCtor, pass.hostEffects.builtinAxioms());
            if (!analyzed.effects().isUnconstrained()) {
                return analyzed.toProfile(exactCtor);
            }
        }
        // Tier 8: Unconstrained default.
        return new JavaCallProfile(EffectSet.EMPTY, false, false, new boolean[paramCount], new boolean[paramCount], false);
    }

    private static final Set<String> REPORTED_REDUNDANT_AXIOMS = Collections.synchronizedSet(new HashSet<>());

    private void reportRedundantCuratedAxiom(Executable exec, AxiomData.MemberAxiom curated) {
        String key = exec.getDeclaringClass().getName() + "." + (exec instanceof Constructor<?> ? "new" : exec.getName())
                + Arrays.toString(exec.getParameterTypes());
        if (!REPORTED_REDUNDANT_AXIOMS.add(key)) {
            return;
        }
        BytecodeEffectAnalyzer.Result inferred = BytecodeEffectAnalyzer.analyzeRaw(exec, pass.hostEffects.builtinAxioms());
        if (BytecodeEffectAnalyzer.isAtLeastAsStrongAs(inferred, curated, exec)) {
            System.err.println("Axiom report : " + key + " curated axiom can be inferred as is or stronger");
        }
    }

    private static HostEffects.OverrideEntry lookupHierarchyMethod(
            Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> table, Class<?> owner, String methodName) {
        if (table.isEmpty() || owner == null) {
            return null;
        }
        HostEffects.OverrideEntry direct = table.get(HostEffects.JavaTarget.ofMethod(owner, methodName));
        if (direct != null) {
            return direct;
        }
        for (Class<?> c = owner.getSuperclass(); c != null; c = c.getSuperclass()) {
            HostEffects.OverrideEntry found = table.get(HostEffects.JavaTarget.ofMethod(c, methodName));
            if (found != null) {
                return found;
            }
        }
        Deque<Class<?>> queue = new ArrayDeque<>(List.of(owner.getInterfaces()));
        Set<Class<?>> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Class<?> iface = queue.poll();
            if (!visited.add(iface)) {
                continue;
            }
            HostEffects.OverrideEntry found = table.get(HostEffects.JavaTarget.ofMethod(iface, methodName));
            if (found != null) {
                return found;
            }
            Collections.addAll(queue, iface.getInterfaces());
        }
        return null;
    }

    private static JavaCallProfile profileFromOverride(HostEffects.OverrideEntry entry,
                                                       Executable exec,
                                                       boolean explicitNothrow) {
        int paramCount = exec != null ? exec.getParameterCount() : 0;
        boolean[] mutParams = new boolean[paramCount];
        boolean[] borrowParams = new boolean[paramCount];
        if (entry.io()) {
            return new JavaCallProfile(EffectSet.IO_DECLARED, false, false, mutParams, borrowParams, false);
        }
        boolean isStaticOrCtor = exec instanceof Constructor<?> || (exec != null && Modifier.isStatic(exec.getModifiers()));
        boolean mutReceiver = !isStaticOrCtor && entry.mutReceiver() && exec.getDeclaringClass() != String.class;
        // If static method had `mut` in an axiom, attribute mut to parameter 0 when present.
        if (isStaticOrCtor && !(exec instanceof Constructor<?>) && entry.mutReceiver() && paramCount > 0) {
            mutParams[0] = true;
        }
        boolean borrowAll = entry.borrowReceiver();
        if (borrowAll) {
            for (int i = 0; i < paramCount; i++) {
                borrowParams[i] = true;
            }
        }
        boolean[] uniqueParams = new boolean[paramCount];
        if (exec != null) {
            Parameter[] params = exec.getParameters();
            for (int i = 0; i < params.length; i++) {
                if (params[i].isAnnotationPresent(Mut.class) && params[i].getType() != String.class) {
                    mutParams[i] = true;
                }
                if (params[i].isAnnotationPresent(Shared.class)) {
                    borrowParams[i] = false;
                }
                if (params[i].isAnnotationPresent(Unique.class)) {
                    uniqueParams[i] = true;
                    borrowParams[i] = false;
                }
            }
        }
        EffectSet set = EffectSet.ofDeclared(entry.effects(), mutReceiver, isStaticOrCtor || borrowAll,
                !isStaticOrCtor && !borrowAll, entry.uniqueResult(), false, entry.volatileEffect(), true);
        return new JavaCallProfile(set, mutReceiver, !isStaticOrCtor && borrowAll, mutParams, borrowParams, uniqueParams, explicitNothrow);
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
            if (p.isAnnotationPresent(Mut.class) || p.isAnnotationPresent(Shared.class) || p.isAnnotationPresent(Unique.class)) {
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

    private static JavaCallProfile profileFromAnnotations(Executable exec, Class<?> classSource, boolean classLevelOnly) {
        int paramCount = exec != null ? exec.getParameterCount() : 0;
        boolean[] mutParams = new boolean[paramCount];
        boolean[] borrowParams = new boolean[paramCount];
        boolean[] uniqueParams = new boolean[paramCount];
        AnnotatedElement primary = (!classLevelOnly && exec != null) ? exec : classSource;
        if (primary.isAnnotationPresent(IO.class)) {
            return new JavaCallProfile(EffectSet.IO_DECLARED, false, false, mutParams, borrowParams, uniqueParams, false);
        }
        EnumSet<Effect> effects = EnumSet.noneOf(Effect.class);
        collectAnnotationsInto(classSource, effects);
        if (!classLevelOnly && exec != null) {
            collectAnnotationsInto(exec, effects);
        }
        boolean isStaticOrCtor = exec instanceof Constructor<?> || (exec != null && Modifier.isStatic(exec.getModifiers()));
        boolean mutReceiver = !classLevelOnly && exec instanceof Method && !isStaticOrCtor
                && exec.isAnnotationPresent(Mut.class) && exec.getDeclaringClass() != String.class;
        boolean explicitVolatile = (!classLevelOnly && exec != null && exec.isAnnotationPresent(Volatile.class))
                || (classSource != null && classSource.isAnnotationPresent(Volatile.class)
                    && (classLevelOnly || exec == null || !exec.isAnnotationPresent(Pure.class)));
        boolean explicitShared = (!classLevelOnly && exec != null && exec.isAnnotationPresent(Shared.class))
                || (classSource != null && classSource.isAnnotationPresent(Shared.class));
        boolean uniqueResult = !classLevelOnly && exec != null && (exec.isAnnotationPresent(Unique.class)
                || (exec instanceof Constructor<?> && effects.contains(Effect.HERMETIC) && allParamsPrimitiveOrString(exec)));
        if (exec != null) {
            Parameter[] params = exec.getParameters();
            for (int i = 0; i < params.length; i++) {
                if (params[i].isAnnotationPresent(Mut.class) && params[i].getType() != String.class) {
                    mutParams[i] = true;
                }
                if (params[i].isAnnotationPresent(Unique.class)) {
                    uniqueParams[i] = true;
                    borrowParams[i] = false;
                } else {
                    // Annotated Java methods default parameters to @BORROWED unless @Shared is written (BIP016-D8).
                    borrowParams[i] = !params[i].isAnnotationPresent(Shared.class) && !explicitShared;
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
        EffectSet set = EffectSet.ofDeclared(effects, mutReceiver, isStaticOrCtor || !explicitShared, !isStaticOrCtor && explicitShared,
                uniqueResult, false, explicitVolatile, true);
        return new JavaCallProfile(set, mutReceiver, borrowReceiver, mutParams, borrowParams, uniqueParams, explicitNothrow);
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



    Type.Function applySamJavaEffects(Method samMethod, Type.Function rawSam) {
        JavaCallProfile profile = resolveJavaMethodProfile(samMethod.getDeclaringClass(), samMethod.getName(), samMethod);
        if (profile.effects().isUnconstrained() && !anyTrue(profile.mutParams()) && !anyTrue(profile.borrowParams()) && !anyTrue(profile.uniqueParams())) {
            return rawSam;
        }
        // Only attach explicit annotations/overrides on the SAM interface method itself (not Object defaults).
        if (!profile.effects().hasExplicitAnnotations() && !anyTrue(profile.mutParams()) && !anyTrue(profile.borrowParams()) && !anyTrue(profile.uniqueParams())) {
            return rawSam;
        }
        List<Type.Parameter> updated = new ArrayList<>(rawSam.parameters().size());
        for (int i = 0; i < rawSam.parameters().size(); i++) {
            Type.Parameter p = rawSam.parameters().get(i);
            boolean mut = i < profile.mutParams().length && profile.mutParams()[i];
            boolean borrow = i < profile.borrowParams().length && profile.borrowParams()[i];
            boolean unique = i < profile.uniqueParams().length && profile.uniqueParams()[i];
            updated.add(new Type.Parameter(p.name(), p.type(), p.optional(), p.keywordOnly(), mut, borrow, !borrow && !unique, unique));
        }
        EffectSet fnEffects = new EffectSet(profile.effects().effects(), profile.effects().declared(),
                false, false, false,
                profile.effects().uniqueResult(), profile.effects().explicitUniqueResult(),
                profile.effects().terminating(), profile.effects().isIO(), profile.effects().explicitVolatile(), false);
        return new Type.Function(rawSam.typeParameters(), updated, rawSam.result(), fnEffects);
    }

    // ================================================================ 5. Frame-Local Closure Classification

    private void classifyClosures(BabbleParser.FileContext file) {
        List<BabbleParser.FunctionLiteralContext> allFns = new ArrayList<>();
        collectFunctionLiterals(file, allFns);

        // First identify named local closures `def h = (...) -> (...)` inside an enclosing function F.
        Map<BabbleParser.DefExpressionContext, BabbleParser.FunctionLiteralContext> localFnDefs = new IdentityHashMap<>();
        for (BabbleParser.FunctionLiteralContext fn : allFns) {
            BabbleParser.FunctionLiteralContext enclosing = checker.enclosingFunctionOf(fn);
            if (enclosing == null) {
                continue;
            }
            if (fn.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == fn) {
                ScopeNode defScope = pass.definitionScopes.get(def);
                if (defScope != null
                        && defScope.kind != ScopeNode.Kind.FILE
                        && defScope.kind != ScopeNode.Kind.CLASS_BODY
                        && defScope.kind != ScopeNode.Kind.INTERFACE_BODY
                        && defScope.kind != ScopeNode.Kind.STATIC_BODY
                        && !checker.isReassigned(def)) {
                    localFnDefs.put(def, fn);
                }
            } else if (isNonEscapingInlineLambda(fn)) {
                frameLocalOwner.put(fn, enclosing);
            }
        }

        // Check if every reference to `def h` stays a direct call or borrow-slot argument inside `enclosing`.
        Set<BabbleParser.DefExpressionContext> escapingDefs = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockScopes.Occurrence occ : pass.occurrences) {
            Object resolved = checker.resolveNameForEffects(occ.name(), occ.scope());
            if (resolved instanceof BabbleParser.DefExpressionContext def && localFnDefs.containsKey(def)) {
                if (occ.ctx() == def) {
                    continue;
                }
                if (!isDirectCalleeOrBorrowArg(occ.ctx())) {
                    escapingDefs.add(def);
                }
            }
        }
        for (Map.Entry<BabbleParser.DefExpressionContext, BabbleParser.FunctionLiteralContext> entry : localFnDefs.entrySet()) {
            if (!escapingDefs.contains(entry.getKey())) {
                BabbleParser.FunctionLiteralContext enclosing = checker.enclosingFunctionOf(entry.getValue());
                if (enclosing != null) {
                    frameLocalOwner.put(entry.getValue(), enclosing);
                }
            }
        }

        // Mark local defs captured by any *escaping* closure so they are not treated as private frame locals.
        Resolution resolution = pass.analysis.resolution();
        if (resolution != null) {
            for (BabbleParser.FunctionLiteralContext fn : allFns) {
                if (!frameLocalOwner.containsKey(fn)) {
                    for (Resolution.Variable cap : resolution.captured(fn)) {
                        if (cap.declaration() != null) {
                            escapedCapturedLocals.add(cap.declaration());
                        }
                    }
                }
            }
        }
    }

    private boolean isNonEscapingInlineLambda(BabbleParser.FunctionLiteralContext fn) {
        return isDirectCalleeOrBorrowArg(fn);
    }

    private boolean isDirectCalleeOrBorrowArg(ParserRuleContext expr) {
        ParserRuleContext p = expr;
        while (p.getParent() instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            p = block;
        }
        if (p.getParent() instanceof BabbleParser.CallContext call && call.expression() == p) {
            return true;
        }
        if (p.getParent() instanceof BabbleParser.CallParameterContext cp
                && cp.getParent() instanceof BabbleParser.CallParametersContext cps
                && cps.getParent() instanceof BabbleParser.CallContext call) {
            return isBorrowCallSlot(call, cp);
        }
        return false;
    }

    private boolean isBorrowCallSlot(BabbleParser.CallContext call, BabbleParser.CallParameterContext cp) {
        Type callee = checker.rawTypeOf(call.expression());
        if (callee instanceof Type.Function fn) {
            int paramIdx = checker.parameterIndexForArg(fn.parameters(), call.callParameters().callParameter(), cp);
            if (paramIdx >= 0 && paramIdx < fn.parameters().size()) {
                if (fn.parameters().get(paramIdx).borrow()) {
                    return true;
                }
                BabbleParser.FunctionLiteralContext targetLit = checker.literalOf(fn);
                if (targetLit != null && checker.inFile(targetLit)) {
                    // Check whether targetLit itself never lets parameter `paramIdx` escape!
                    return parameterNeverEscapesSyntactically(targetLit, paramIdx);
                }
            }
        }
        return false;
    }

    private boolean parameterNeverEscapesSyntactically(BabbleParser.FunctionLiteralContext lit, int paramIdx) {
        List<BabbleParser.FnParamContext> params = lit.fnParameters().fnParam();
        if (paramIdx < 0 || paramIdx >= params.size()) {
            return false;
        }
        BabbleParser.FnParamContext targetParam = params.get(paramIdx);
        for (BlockScopes.Occurrence occ : pass.occurrences) {
            if (checker.resolveNameForEffects(occ.name(), occ.scope()) == targetParam) {
                if (!(occ.ctx().getParent() instanceof BabbleParser.CallContext c && c.expression() == occ.ctx())) {
                    return false;
                }
            }
        }
        return true;
    }

    private Set<BabbleParser.FunctionLiteralContext> activeFrameChain(BabbleParser.FunctionLiteralContext start) {
        Set<BabbleParser.FunctionLiteralContext> chain = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BabbleParser.FunctionLiteralContext cur = frameLocalOwner.get(start); cur != null; cur = frameLocalOwner.get(cur)) {
            if (!chain.add(cur)) {
                break;
            }
        }
        return chain;
    }

    // ================================================================ 6. Call Graph & SCC Fixed Point

    private static void collectFunctionLiterals(ParseTree tree, List<BabbleParser.FunctionLiteralContext> out) {
        if (tree instanceof BabbleParser.FunctionLiteralContext fn) {
            out.add(fn);
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectFunctionLiterals(tree.getChild(i), out);
        }
    }

    private static void collectCallables(ParseTree tree, List<ParserRuleContext> out) {
        if (tree instanceof BabbleParser.FunctionLiteralContext fn) {
            out.add(fn);
        } else if (tree instanceof BabbleParser.ClassExpressionContext cls) {
            out.add(cls);
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectCallables(tree.getChild(i), out);
        }
    }

    private boolean isFixedSummary(ParserRuleContext callable) {
        if (callable instanceof BabbleParser.FunctionLiteralContext fn) {
            ParsedPrefix prefix = parseFunctionPrefix(fn, false);
            return prefix.axiom();
        }
        return false;
    }

    private CallableSummary initialSummary(ParserRuleContext callable) {
        if (callable instanceof BabbleParser.FunctionLiteralContext fn) {
            ParsedPrefix prefix = parseFunctionPrefix(fn, true);
            List<BabbleParser.FnParamContext> params = fn.fnParameters().fnParam();
            int n = params.size();
            boolean[] mutParams = new boolean[n];
            boolean[] borrowParams = new boolean[n];
            boolean[] uniqueParams = new boolean[n];
            for (int i = 0; i < n; i++) {
                ParamMods pm = parseParamMods(params.get(i), true);
                mutParams[i] = pm.mut();
                uniqueParams[i] = pm.unique();
                borrowParams[i] = !pm.shared() && !pm.unique();
            }
            if (prefix.axiom()) {
                EffectSet set = prefix.toEffectSet();
                return new CallableSummary(set, mutParams, borrowParams, uniqueParams);
            }
            // Optimistic start for inference/verification: {HERMETIC, DETERMINISTIC, NOTHROW}, borrow all non-shared/non-unique, uniqueResult, @TERMINATING!
            EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
            boolean borrowThis = !prefix.explicitSharedThis();
            EffectSet set = new EffectSet(opt, prefix.effects(), prefix.mutThis(), borrowThis, prefix.explicitSharedThis(),
                    true, prefix.uniqueReturn(), true, false, false, false);
            return new CallableSummary(set, mutParams, borrowParams, uniqueParams);
        }
        // ClassExpressionContext constructor:
        BabbleParser.ClassExpressionContext cls = (BabbleParser.ClassExpressionContext) callable;
        List<BabbleParser.ClassParamContext> params = cls.classParameters() != null
                ? cls.classParameters().classParam() : List.of();
        int n = params.size();
        boolean[] mutParams = new boolean[n];
        boolean[] borrowParams = new boolean[n];
        boolean[] uniqueParams = new boolean[n];
        TypeNode node = pass.typeNodes.get(cls);
        boolean isValue = node != null && node.kind == ClassShape.Kind.VALUE_CLASS;
        BitSet ctorStored = new BitSet();
        for (int i = 0; i < n; i++) {
            ParamMods pm = parseParamMods(params.get(i), true);
            if (isValue && pm.mut()) {
                checker.effectDiagnostic("value class constructor parameter cannot be mut : " + params.get(i).ID().getText(), params.get(i));
            }
            mutParams[i] = !isValue && pm.mut();
            uniqueParams[i] = pm.unique();
            boolean storedAsField = isValue || params.get(i).DEF() != null;
            Type pType = checker.typeOfDeclarationForEffects(params.get(i));
            if (storedAsField && !uniqueParams[i] && !isDeeplyImmutable(pType)) {
                ctorStored.set(i);
            }
            borrowParams[i] = !pm.shared() && !uniqueParams[i];
        }
        EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
        return new CallableSummary(EffectSet.ofInferred(opt, false, true, ctorStored.isEmpty(), true), mutParams, borrowParams, uniqueParams,
                Set.of(), Set.of(), false, EMPTY_SUMMARY_BITS, false, false, false, ctorStored);
    }

    private Map<ParserRuleContext, Set<ParserRuleContext>> buildCallGraph(List<ParserRuleContext> callables) {
        Set<ParserRuleContext> callableSet = Collections.newSetFromMap(new IdentityHashMap<>());
        callableSet.addAll(callables);
        Map<ParserRuleContext, Set<ParserRuleContext>> edges = new IdentityHashMap<>();
        for (ParserRuleContext c : callables) {
            Set<ParserRuleContext> targets = Collections.newSetFromMap(new IdentityHashMap<>());
            collectCallsInCallable(c, c, callableSet, targets);
            edges.put(c, targets);
        }
        return edges;
    }

    private void collectCallsInCallable(ParseTree tree, ParserRuleContext owner,
                                        Set<ParserRuleContext> callableSet, Set<ParserRuleContext> out) {
        if (tree != owner && (tree instanceof BabbleParser.FunctionLiteralContext || tree instanceof BabbleParser.ClassExpressionContext)) {
            if (tree instanceof BabbleParser.FunctionLiteralContext innerFn && frameLocalOwner.containsKey(innerFn)) {
                out.add(innerFn);
            }
            return;
        }
        if (tree instanceof BabbleParser.CallContext call) {
            if (call.expression() instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
                Object resolved = checker.resolveNameForEffects(sel.ID().getText(), checker.occurrenceScopeOf(sel));
                if (resolved instanceof BabbleParser.DefExpressionContext def
                        && def.value instanceof BabbleParser.FunctionLiteralContext lit
                        && callableSet.contains(lit)) {
                    out.add(lit);
                }
            }
            Type callee = checker.rawTypeOf(call.expression());
            for (Type m : Type.membersOf(callee)) {
                if (m instanceof Type.Function fn) {
                    BabbleParser.FunctionLiteralContext lit = checker.literalOf(fn);
                    if (lit != null && callableSet.contains(lit)) {
                        out.add(lit);
                    }
                } else if (m instanceof Type.Overloaded ov) {
                    for (Type.Function fn : ov.alternatives()) {
                        BabbleParser.FunctionLiteralContext lit = checker.literalOf(fn);
                        if (lit != null && callableSet.contains(lit)) {
                            out.add(lit);
                        }
                    }
                } else if (m instanceof Type.ClassValue cv && cv.node().ctx instanceof BabbleParser.ClassExpressionContext cls
                        && callableSet.contains(cls)) {
                    out.add(cls);
                }
            }
            for (BabbleParser.CallParameterContext arg : call.callParameters().callParameter()) {
                Type argType = checker.rawTypeOf(arg.expression());
                for (Type m : Type.membersOf(argType)) {
                    if (m instanceof Type.Function fn) {
                        BabbleParser.FunctionLiteralContext lit = checker.literalOf(fn);
                        if (lit != null && callableSet.contains(lit)) {
                            out.add(lit);
                        }
                    }
                }
            }
        } else if (tree instanceof BabbleParser.SelectorContext sel && sel.expression() != null) {
            // Possible Babble `.toString()` invocation or field access.
            Type recv = checker.rawTypeOf(sel.expression());
            for (Type m : Type.membersOf(recv)) {
                if (m instanceof Type.Instance inst) {
                    TypeNode.Member mem = inst.node().members.get(ScopeAnalysis.selectorName(sel));
                    if (mem != null && mem.function != null && callableSet.contains(mem.function)) {
                        out.add(mem.function);
                    }
                }
            }
        } else if (tree instanceof BabbleParser.BinaryOpContext bin && "+".equals(bin.op.getText())) {
            addToStringTargets(checker.rawTypeOf(bin.left), callableSet, out);
            addToStringTargets(checker.rawTypeOf(bin.right), callableSet, out);
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectCallsInCallable(tree.getChild(i), owner, callableSet, out);
        }
    }

    private void addToStringTargets(Type type, Set<ParserRuleContext> callableSet, Set<ParserRuleContext> out) {
        if (type == null) {
            return;
        }
        for (Type m : Type.membersOf(type)) {
            if (m instanceof Type.Instance inst) {
                TypeNode.Member ts = checker.findMemberOnNode(inst.node(), "toString");
                if (ts != null && ts.function != null && callableSet.contains(ts.function)) {
                    out.add(ts.function);
                }
            }
        }
    }

    private static List<List<ParserRuleContext>> tarjanScc(List<ParserRuleContext> nodes,
                                                           Map<ParserRuleContext, Set<ParserRuleContext>> edges) {
        Map<ParserRuleContext, Integer> indexMap = new IdentityHashMap<>();
        Map<ParserRuleContext, Integer> lowlink = new IdentityHashMap<>();
        Set<ParserRuleContext> onStack = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<ParserRuleContext> stack = new ArrayDeque<>();
        List<List<ParserRuleContext>> result = new ArrayList<>();
        int[] index = {0};

        class Dfs {
            void visit(ParserRuleContext v) {
                indexMap.put(v, index[0]);
                lowlink.put(v, index[0]);
                index[0]++;
                stack.push(v);
                onStack.add(v);
                for (ParserRuleContext w : edges.getOrDefault(v, Set.of())) {
                    if (!indexMap.containsKey(w)) {
                        visit(w);
                        lowlink.put(v, Math.min(lowlink.get(v), lowlink.get(w)));
                    } else if (onStack.contains(w)) {
                        lowlink.put(v, Math.min(lowlink.get(v), indexMap.get(w)));
                    }
                }
                if (lowlink.get(v).equals(indexMap.get(v))) {
                    List<ParserRuleContext> scc = new ArrayList<>();
                    while (true) {
                        ParserRuleContext w = stack.pop();
                        onStack.remove(w);
                        scc.add(w);
                        if (w == v) {
                            break;
                        }
                    }
                    result.add(scc);
                }
            }
        }
        Dfs dfs = new Dfs();
        for (ParserRuleContext n : nodes) {
            if (!indexMap.containsKey(n)) {
                dfs.visit(n);
            }
        }
        return result;
    }

    // ================================================================ 7. Body Transfer & Alias Analyzer

    private CallableSummary recomputeSummary(ParserRuleContext callable,
                                             CallableSummary current,
                                             Map<ParserRuleContext, Type.Function> specializedParams,
                                             boolean emitDiagnostics) {
        if (callable instanceof BabbleParser.FunctionLiteralContext fn) {
            return analyzeFunctionBody(fn, current, specializedParams, emitDiagnostics);
        }
        return analyzeConstructorBody((BabbleParser.ClassExpressionContext) callable, current, emitDiagnostics);
    }

    private CallableSummary analyzeFunctionBody(BabbleParser.FunctionLiteralContext fn,
                                                CallableSummary current,
                                                Map<ParserRuleContext, Type.Function> specializedParams,
                                                boolean emitDiagnostics) {
        ParsedPrefix prefix = parseFunctionPrefix(fn, false);
        List<BabbleParser.FnParamContext> params = fn.fnParameters().fnParam();
        boolean untyped = isCompletelyUntyped(fn) && specializedParams == null;

        boolean[] mutParams = current.mutParams().clone();
        boolean[] borrowParams = current.borrowParams().clone();
        boolean mutThis = current.effects().mutThis();
        boolean borrowThis = current.effects().borrowThis();

        BodyAnalyzer analyzer = new BodyAnalyzer(
                fn,
                checker.scopeOf(fn),
                params,
                untyped,
                mutParams,
                borrowParams,
                current.uniqueParams(),
                mutThis,
                borrowThis,
                prefix,
                activeFrameChain(fn),
                specializedParams,
                emitDiagnostics);

        // Parameter defaults execute when omitted at call sites.
        for (BabbleParser.FnParamContext p : params) {
            if (p.defaultValue != null) {
                analyzer.eval(p.defaultValue);
            }
        }
        Origin bodyResult = analyzer.evalBlock(fn.functionBlock, true);
        analyzer.recordReturnOrigin(bodyResult, fn.functionBlock);

        EnumSet<Effect> nextEffects = EnumSet.noneOf(Effect.class);
        if (analyzer.hermetic) {
            nextEffects.add(Effect.HERMETIC);
        }
        if (analyzer.deterministic) {
            nextEffects.add(Effect.DETERMINISTIC);
        }
        if (analyzer.nothrow) {
            nextEffects.add(Effect.NOTHROW);
        }
        EffectSet nextSet = new EffectSet(
                nextEffects,
                prefix.effects(),
                analyzer.mutThis,
                analyzer.borrowThis,
                prefix.explicitSharedThis(),
                analyzer.uniqueResult,
                prefix.uniqueReturn(),
                analyzer.terminating,
                false,
                false,
                false);
        return new CallableSummary(nextSet, analyzer.mutParams, analyzer.borrowParams, current.uniqueParams(),
                Set.copyOf(analyzer.frameReadOwners), Set.copyOf(analyzer.frameWriteOwners), analyzer.deepMutThis,
                (BitSet) analyzer.returnParams.clone(), analyzer.returnThis, analyzer.returnExternal, true, EMPTY_SUMMARY_BITS,
                analyzer.readParams, analyzer.readsThis);
    }

    private CallableSummary analyzeConstructorBody(BabbleParser.ClassExpressionContext cls,
                                                   CallableSummary current,
                                                   boolean emitDiagnostics) {
        TypeNode node = pass.typeNodes.get(cls);
        boolean isValue = node != null && node.kind == ClassShape.Kind.VALUE_CLASS;
        List<BabbleParser.ClassParamContext> params = cls.classParameters() != null
                ? cls.classParameters().classParam() : List.of();
        boolean[] mutParams = current.mutParams().clone();
        boolean[] borrowParams = current.borrowParams().clone();
        ParsedPrefix pseudoPrefix = isValue
                ? new ParsedPrefix(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC),
                        false, false, false, false, false, false, false, true)
                : ParsedPrefix.EMPTY;

        BodyAnalyzer analyzer = new BodyAnalyzer(
                cls,
                checker.scopeOf(cls),
                params,
                false,
                mutParams,
                borrowParams,
                current.uniqueParams(),
                true, // constructor initializes its own fresh `this` fields!
                false,
                pseudoPrefix,
                Set.of(),
                null,
                emitDiagnostics);
        analyzer.constructingSelf = true;

        for (int i = 0; i < params.size(); i++) {
            BabbleParser.ClassParamContext p = params.get(i);
            if (p.defaultValue != null) {
                analyzer.eval(p.defaultValue);
            }
            if ((isValue || p.DEF() != null) && !current.uniqueParams()[i] && !isDeeplyImmutable(checker.typeOfDeclarationForEffects(p))) {
                analyzer.ctorStoredParams.set(i);
            }
        }
        analyzer.evalClassBody(cls.classBlock);
        if (cls.staticBlock != null) {
            // Static block runs once at class definition time; checked separately in verify(), not per construction.
        }

        EnumSet<Effect> nextEffects = EnumSet.noneOf(Effect.class);
        if (analyzer.hermetic) {
            nextEffects.add(Effect.HERMETIC);
        }
        if (analyzer.deterministic) {
            nextEffects.add(Effect.DETERMINISTIC);
        }
        if (analyzer.nothrow) {
            nextEffects.add(Effect.NOTHROW);
        }
        boolean ctorUnique = analyzer.hermetic && analyzer.ctorStoredParams.isEmpty();
        EffectSet nextSet = EffectSet.ofInferred(nextEffects, false, true, ctorUnique, analyzer.terminating);
        return new CallableSummary(nextSet, analyzer.mutParams, analyzer.borrowParams, current.uniqueParams(),
                Set.of(), Set.of(), false, EMPTY_SUMMARY_BITS, false, false, false, (BitSet) analyzer.ctorStoredParams.clone(),
                analyzer.readParams, false);
    }

    private CallableSummary finalizeSummary(ParserRuleContext callable, CallableSummary raw) {
        if (isFixedSummary(callable)) {
            return raw;
        }
        EnumSet<Effect> effs = EffectSet.copySet(raw.effects().effects());
        boolean anyMutParam = anyTrue(raw.mutParams());
        boolean mutThis = raw.effects().mutThis();
        if (effs.contains(Effect.HERMETIC) && !mutThis && !anyMutParam) {
            effs.add(Effect.PURE);
        } else {
            effs.remove(Effect.PURE);
        }

        // Compositional algebraic property inference:
        if (callable instanceof BabbleParser.FunctionLiteralContext fn) {
            inferAlgebraicProperties(fn, effs, raw.mutParams(), mutThis);
        } else if (callable instanceof BabbleParser.ClassExpressionContext cls) {
            TypeNode node = pass.typeNodes.get(cls);
            if (node != null && node.kind == ClassShape.Kind.VALUE_CLASS
                    && effs.contains(Effect.PURE) && effs.contains(Effect.DETERMINISTIC)
                    && cls.classParameters() != null
                    && !cls.classParameters().classParam().isEmpty()
                    && cls.classParameters().classParam().get(0).defaultValue == null) {
                // Value class constructor stores parameter 0 losslessly into field 0 -> injective!
                effs.add(Effect.INJECTIVE);
            }
        }

        EffectSet finalized = new EffectSet(
                effs,
                raw.effects().declared(),
                mutThis,
                raw.effects().borrowThis(),
                raw.effects().explicitSharedThis(),
                raw.effects().uniqueResult(),
                raw.effects().explicitUniqueResult(),
                raw.effects().terminating(),
                false,
                false,
                false);
        return new CallableSummary(finalized, raw.mutParams(), raw.borrowParams(), raw.uniqueParams(),
                raw.frameReadOwners(), raw.frameWriteOwners(), raw.deepMutThis(),
                raw.returnParams(), raw.returnThis(), raw.returnExternal(), raw.returnKnown(), raw.ctorStoredParams(),
                raw.readParams(), raw.readsThis());
    }

    private CallableSummary buildVisibleSummary(ParserRuleContext callable, CallableSummary internal) {
        if (!(callable instanceof BabbleParser.FunctionLiteralContext fn)) {
            return internal;
        }
        if (isCompletelyUntyped(fn)) {
            return internal;
        }
        ParsedPrefix prefix = parseFunctionPrefix(fn, false);
        if (prefix.axiom()) {
            return internal;
        }
        // Typed function (BIP016-D2/D3/D4): lock explicit opt-outs (io, volatile, mut, shared, unique),
        // while preserving inferred @NOTHROW, @TERMINATING, algebraic traits, reads, and frame-closure provenance.
        List<BabbleParser.FnParamContext> params = fn.fnParameters().fnParam();
        boolean[] visMut = new boolean[params.size()];
        boolean[] visBorrow = new boolean[params.size()];
        boolean[] visUnique = new boolean[params.size()];
        for (int i = 0; i < params.size(); i++) {
            ParamMods pm = parseParamMods(params.get(i), false);
            visMut[i] = pm.mut();
            visUnique[i] = pm.unique();
            visBorrow[i] = !pm.shared() && !pm.unique() && internal.borrowParams()[i];
        }
        boolean ifaceDefault = isInterfaceDefault(fn);
        EnumSet<Effect> effs = ifaceDefault
                ? EffectSet.copySet(prefix.effects())
                : EffectSet.copySet(internal.effects().effects());
        EnumSet<Effect> decl = EffectSet.copySet(prefix.effects());
        boolean mutThis = prefix.mutThis();
        boolean borrowThis = !prefix.explicitSharedThis() && internal.effects().borrowThis();
        boolean uniqueResult = ifaceDefault
                ? prefix.uniqueReturn()
                : (prefix.uniqueReturn() || (!prefix.io() && internal.effects().uniqueResult()));
        boolean terminating = !prefix.io() && internal.effects().terminating();
        if (prefix.io()) {
            effs.remove(Effect.PURE);
            effs.remove(Effect.HERMETIC);
            effs.remove(Effect.DETERMINISTIC);
            if (!prefix.effects().contains(Effect.NOTHROW)) {
                effs.remove(Effect.NOTHROW);
            }
            effs.retainAll(prefix.effects());
        } else if (prefix.explicitVolatile()) {
            effs.remove(Effect.DETERMINISTIC);
            if (mutThis || anyTrue(visMut)) {
                effs.remove(Effect.PURE);
            }
        } else {
            if (mutThis || anyTrue(visMut)) {
                effs.remove(Effect.PURE);
            }
        }
        EffectSet visSet = new EffectSet(
                effs, decl,
                mutThis, borrowThis, prefix.explicitSharedThis(),
                uniqueResult, prefix.uniqueReturn(),
                terminating,
                prefix.io(), prefix.explicitVolatile(), false);
        return new CallableSummary(visSet, visMut, visBorrow, visUnique,
                internal.frameReadOwners(), internal.frameWriteOwners(), internal.deepMutThis(),
                internal.returnParams(), internal.returnThis(), internal.returnExternal(), internal.returnKnown(),
                internal.ctorStoredParams(), internal.readParams(), internal.readsThis());
    }

    // ================================================================ 8. Verification Pass

    void verify(BabbleParser.FileContext file) {
        // 0. Warn on deprecated `axiom ... on ...` / `io on ...` syntax (BIP019 §3.2).
        for (BlockScopes.PendingEffectDecl pending : pass.effectDeclarations) {
            BabbleParser.EffectDeclarationContext ctx = pending.ctx();
            boolean isIo = ctx.effectClause() != null && ctx.effectClause().IO() != null;
            checker.effectWarning(isIo
                    ? "Deprecated syntax : 'io on ...' is deprecated, use 'axiom Target: fn io' instead"
                    : "Deprecated syntax : 'axiom ... on ...' is deprecated, use 'axiom Target: fn ...' instead", ctx);
        }

        // 1. Validate algebraic modifier applicability across all FunctionTypeContext nodes in file.
        validateAllFunctionTypes(file);

        // 2. Verify all callables (annotated functions, value class constructors, mut/borrow/exclusivity).
        List<ParserRuleContext> callables = new ArrayList<>();
        collectCallables(file, callables);
        for (ParserRuleContext callable : callables) {
            verifyCallable(callable);
        }

        // 3. Verify top-level / package / class static blocks for static mut exclusivity and parameter rules.
        verifyTopLevelAndStatics(file);

        // 4. Populate call-site EffectSet map on ScopeAnalysis.
        recordAllCallEffects(file);
    }

    private void validateAllFunctionTypes(ParseTree tree) {
        if (tree instanceof BabbleParser.AxiomExpressionContext) {
            return;
        }
        if (tree instanceof BabbleParser.FunctionTypeContext ft) {
            Type t = checker.annotationTypeOf(ft);
            if (t instanceof Type.Function fn) {
                ParsedPrefix prefix = parseFunctionTypePrefix(ft, fn.effects().borrowThis() || fn.effects().explicitSharedThis(), false);
                validateAlgebraicSignature(prefix.effects(), fn.parameters(), fn.result(),
                        prefix.mutThis(), false, ft);
                validateCallingClause(ft.effectPrefix(), fn.parameters());
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            validateAllFunctionTypes(tree.getChild(i));
        }
    }

    private void validateCallingClause(BabbleParser.EffectPrefixContext effectPrefix,
                                       List<Type.Parameter> params) {
        if (effectPrefix == null || effectPrefix.callingClause() == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (org.antlr.v4.runtime.tree.TerminalNode id : effectPrefix.callingClause().ID()) {
            String name = id.getText();
            if (!seen.add(name)) {
                checker.effectDiagnostic("Duplicate parameter in calling clause : " + name, effectPrefix.callingClause());
                continue;
            }
            Type.Parameter matched = null;
            for (Type.Parameter p : params) {
                if (name.equals(p.name())) {
                    matched = p;
                    break;
                }
            }
            if (matched == null) {
                // A callback of an enclosing function this one captures (BIP017-D5) : no borrow
                // requirement, since capturing it is what the clause is for.
                ParserRuleContext captured = TypeAnnotations.enclosingParameter(effectPrefix, name);
                if (captured == null) {
                    checker.effectDiagnostic("Unknown parameter in calling clause : " + name, effectPrefix.callingClause());
                } else {
                    Type capturedType = capturedParameterType(captured);
                    if (capturedType != null && capturedType != Type.ERROR && !(capturedType instanceof Type.Function)) {
                        checker.effectDiagnostic("Parameter in calling clause must be a function : " + name,
                                effectPrefix.callingClause());
                    }
                }
                continue;
            }
            if (matched.type() != Type.ERROR && !(matched.type() instanceof Type.Function)) {
                checker.effectDiagnostic("Parameter in calling clause must be a function : " + name, effectPrefix.callingClause());
                continue;
            }
            if (!matched.borrow()) {
                checker.effectDiagnostic("Parameter in calling clause must be borrowed : " + name, effectPrefix.callingClause());
            }
        }
    }

    /**
     * The parameters of a function literal with {@code borrow} as declared (no {@code shared} nor
     * {@code unique}) rather than as inferred, so that a callback of a {@code calling} clause that
     * escapes is reported once, as escaping, and not also as not borrowed.
     */
    private List<Type.Parameter> declaredBorrows(BabbleParser.FunctionLiteralContext fn,
                                                 List<Type.Parameter> params) {
        List<BabbleParser.FnParamContext> decls = fn.fnParameters().fnParam();
        if (decls.size() != params.size()) {
            return params;
        }
        List<Type.Parameter> result = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            Type.Parameter p = params.get(i);
            ParamMods declared = parseParamMods(decls.get(i), false);
            boolean borrow = !declared.shared() && !declared.unique();
            result.add(new Type.Parameter(p.name(), p.type(), p.optional(), p.keywordOnly(), p.mut(), borrow,
                    p.explicitShared(), p.unique()));
        }
        return result;
    }

    /** The declared type of a parameter of a function literal or of a function type. */
    private Type capturedParameterType(ParserRuleContext param) {
        if (param instanceof BabbleParser.FnTypeParamContext typeParam
                && typeParam.getParent() instanceof BabbleParser.FnTypeParametersContext list
                && list.getParent() instanceof BabbleParser.FunctionTypeContext ft
                && checker.annotationTypeOf(ft) instanceof Type.Function fn) {
            int index = list.fnTypeParam().indexOf(typeParam);
            return index >= 0 && index < fn.parameters().size() ? fn.parameters().get(index).type() : Type.ERROR;
        }
        return checker.typeOfDeclarationForEffects(param);
    }

    /** Whether {@code fn}'s calling clause names {@code param}, a callback of an enclosing function (BIP017-D5). */
    private static boolean capturesCallback(BabbleParser.FunctionLiteralContext fn, BabbleParser.FnParamContext param) {
        if (fn.effectPrefix() == null || fn.effectPrefix().callingClause() == null || param.ID() == null) {
            return false;
        }
        String name = param.ID().getText();
        return inCallingClause(fn, name)
                && !fn.fnParameters().fnParam().contains(param)
                && TypeAnnotations.enclosingParameter(fn.effectPrefix(), name) == param;
    }

    /** Whether {@code fn}'s calling clause names a callback of an enclosing function (BIP017-D5). */
    private static boolean capturesCallbacks(BabbleParser.FunctionLiteralContext fn) {
        if (fn.effectPrefix() == null || fn.effectPrefix().callingClause() == null) {
            return false;
        }
        for (org.antlr.v4.runtime.tree.TerminalNode id : fn.effectPrefix().callingClause().ID()) {
            String name = id.getText();
            boolean own = fn.fnParameters().fnParam().stream()
                    .anyMatch(p -> p.ID() != null && name.equals(p.ID().getText()));
            if (!own && TypeAnnotations.enclosingParameter(fn.effectPrefix(), name) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean inCallingClause(BabbleParser.FunctionLiteralContext fn, String name) {
        if (fn.effectPrefix() == null || fn.effectPrefix().callingClause() == null) {
            return false;
        }
        for (org.antlr.v4.runtime.tree.TerminalNode id : fn.effectPrefix().callingClause().ID()) {
            if (name.equals(id.getText())) {
                return true;
            }
        }
        return false;
    }

    private static Set<Integer> resolveForwardedParamIndices(BabbleParser.FunctionLiteralContext fn) {
        if (fn.effectPrefix() == null || fn.effectPrefix().callingClause() == null) {
            return Set.of();
        }
        List<BabbleParser.FnParamContext> fnParams = fn.fnParameters().fnParam();
        Set<Integer> indices = new java.util.LinkedHashSet<>();
        for (org.antlr.v4.runtime.tree.TerminalNode id : fn.effectPrefix().callingClause().ID()) {
            String name = id.getText();
            for (int i = 0; i < fnParams.size(); i++) {
                if (name.equals(fnParams.get(i).ID().getText())) {
                    indices.add(i);
                    break;
                }
            }
        }
        return indices.isEmpty() ? Set.of() : Set.copyOf(indices);
    }

    private void verifyCallable(ParserRuleContext callable) {
        if (callable instanceof BabbleParser.FunctionLiteralContext fn) {
            ParsedPrefix prefix = parseFunctionPrefix(fn, false);
            Type rawType = checker.rawTypeOf(fn);
            if (rawType instanceof Type.Function tf) {
                validateCallingClause(fn.effectPrefix(), declaredBorrows(fn, tf.parameters()));
                if (!prefix.io()) {
                    validateAlgebraicSignature(prefix.effects(), tf.parameters(), tf.result(),
                            prefix.mutThis(), prefix.axiom(), fn);
                }
            }
            if (prefix.axiom()) {
                // Axiom body is trusted for effect guarantees, but still check static mut exclusivity inside it!
                recomputeSummary(fn, summaries.get(fn), null, false);
                return;
            }
            // Re-run body analysis with emitDiagnostics = true to report undeclared mut, borrow escape, exclusivity, etc.
            CallableSummary converged = summaries.get(fn);
            CallableSummary verified = finalizeSummary(fn, recomputeSummary(fn, converged, null, true));

            if (!isCompletelyUntyped(fn)) {
                EffectSet actual = verified.effects();
                String fnLabel = ScopeAnalysis.functionName(fn);
                String prefixMsg = fnLabel != null ? "Function " + fnLabel : "Function";
                if (!prefix.io()) {
                    if (!actual.isHermetic()) {
                        if (prefix.explicitVolatile()) {
                            checker.effectDiagnostic(prefixMsg + " declared volatile violates hermetic guarantee : mark function as io", fn);
                        } else {
                            checker.effectDiagnostic(prefixMsg + " violates pure guarantee (performs I/O or external mutation) : mark function as io", fn);
                        }
                    } else if (!prefix.explicitVolatile() && !actual.isDeterministic()) {
                        checker.effectDiagnostic(prefixMsg + " violates deterministic guarantee : mark function as volatile (or io)", fn);
                    }
                }
                // Check explicit additive guarantees (nothrow, algebraic traits):
                for (Effect req : prefix.effects()) {
                    if (req == Effect.PURE || req == Effect.HERMETIC || req == Effect.DETERMINISTIC) {
                        continue;
                    }
                    if (!actual.has(req)) {
                        checker.effectDiagnostic(prefixMsg + " declared " + EffectSet.keywordOf(req)
                                + " violates " + EffectSet.keywordOf(req) + " guarantee", fn);
                    }
                }
                if (prefix.uniqueReturn() && !actual.uniqueResult()) {
                    checker.effectDiagnostic(prefixMsg + " declared unique return violates unique guarantee", fn);
                }
            }
            verifyInheritedAxiomContract(fn, verified);
            return;
        }

        // ClassExpressionContext:
        BabbleParser.ClassExpressionContext cls = (BabbleParser.ClassExpressionContext) callable;
        CallableSummary converged = summaries.get(cls);
        recomputeSummary(cls, converged, null, true);
        TypeNode node = pass.typeNodes.get(cls);
        if (node != null && node.kind == ClassShape.Kind.VALUE_CLASS) {
            if (!converged.effects().isPure()) {
                checker.effectDiagnostic("value class " + node.name + " constructor must be pure", cls);
            } else if (!converged.effects().isDeterministic()) {
                checker.effectDiagnostic("value class " + node.name + " constructor must be deterministic", cls);
            }
        }
    }

    /**
     * Checks a Babble class/object method overriding a Java supertype ({@code Object} or an
     * implemented Java interface) against the most specific inherited non-exact axiom contract
     * ({@code docs/design/bip019_refactoring-axioms.md} §3.2).
     */
    private void verifyInheritedAxiomContract(BabbleParser.FunctionLiteralContext fn, CallableSummary verified) {
        ScopeNode fnScope = pass.scopeNodes.get(fn);
        if (fnScope == null || fnScope.memberOf == null || fnScope.memberOf.isInterface()) {
            return;
        }
        String methodName = ScopeAnalysis.functionName(fn);
        if (methodName == null) {
            return;
        }
        Set<Class<?>> javaSupertypes = collectJavaSupertypes(fnScope.memberOf);
        int arity = fn.fnParameters().fnParam().size();
        AxiomData.MemberAxiom contract = null;
        if (!localAxioms.isEmpty()) {
            contract = AxiomData.lookupInheritedContract(javaSupertypes, methodName, arity, localAxioms::directMembers);
        }
        if (contract == null) {
            contract = pass.hostEffects.builtinAxioms().inheritedContract(javaSupertypes, methodName, arity);
        }
        if (contract == null || !hasJavaMethodWithArity(contract.ownerClass(), methodName, arity)) {
            return;
        }
        EffectSet required = contract.effects();
        if (required.isIO()) {
            return;
        }
        EffectSet actual = verified.effects();
        String axiomLabel = "axiom " + contract.ownerClass().getName() + "." + contract.name();
        String prefixMsg = "Function " + methodName;
        if (required.isPure()) {
            if (!actual.isPure() || actual.mutThis() || anyTrue(verified.mutParams()) || !actual.isHermetic()) {
                checker.effectDiagnostic(prefixMsg + " violates pure guarantee of " + axiomLabel, fn);
                return;
            }
        } else if (required.isHermetic() && !actual.isHermetic()) {
            checker.effectDiagnostic(prefixMsg + " violates hermetic guarantee of " + axiomLabel, fn);
            return;
        }
        if (required.isDeterministic() && !actual.isDeterministic()) {
            checker.effectDiagnostic(prefixMsg + " violates deterministic guarantee of " + axiomLabel, fn);
        }
        if (required.isNothrow() && !actual.isNothrow()) {
            checker.effectDiagnostic(prefixMsg + " violates nothrow guarantee of " + axiomLabel, fn);
        }
        for (Effect req : required.effects()) {
            if (req == Effect.IDEMPOTENT || req == Effect.COMMUTATIVE || req == Effect.ASSOCIATIVE
                    || req == Effect.MONOTONE || req == Effect.INJECTIVE) {
                if (!actual.has(req)) {
                    checker.effectDiagnostic(prefixMsg + " violates " + EffectSet.keywordOf(req) + " guarantee of " + axiomLabel, fn);
                }
            }
        }
        if (required.uniqueResult() && !actual.uniqueResult()) {
            checker.effectDiagnostic(prefixMsg + " violates unique guarantee of " + axiomLabel, fn);
        }
        if (contract.borrowReceiver() && !actual.borrowThis()) {
            checker.effectDiagnostic(prefixMsg + " violates borrow guarantee of " + axiomLabel, fn);
        }
        boolean[] actualBorrowParams = verified.borrowParams();
        boolean[] actualMutParams = verified.mutParams();
        for (int i = 0; i < actualBorrowParams.length; i++) {
            boolean reqBorrow = contract.hasSignature()
                    ? (i < contract.borrowParams().length && contract.borrowParams()[i])
                    : !contract.sharedReceiver();
            boolean allowMut = contract.hasSignature()
                    && i < contract.mutParams().length && contract.mutParams()[i];
            if (actualMutParams[i] && !allowMut) {
                checker.effectDiagnostic(prefixMsg + " violates pure guarantee of " + axiomLabel, fn);
                break;
            }
            if (reqBorrow && !actualBorrowParams[i]) {
                checker.effectDiagnostic(prefixMsg + " violates borrow guarantee of " + axiomLabel, fn);
                break;
            }
        }
    }

    private static Set<Class<?>> collectJavaSupertypes(TypeNode node) {
        Set<Class<?>> result = new LinkedHashSet<>();
        result.add(Object.class);
        collectJavaSupertypesInto(node, result, Collections.newSetFromMap(new IdentityHashMap<>()));
        return result;
    }

    private static void collectJavaSupertypesInto(TypeNode node, Set<Class<?>> out, Set<TypeNode> visited) {
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

    private static boolean hasJavaMethodWithArity(Class<?> owner, String name, int arity) {
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

    private void verifyTopLevelAndStatics(BabbleParser.FileContext file) {
        // Run BodyAnalyzer in unconstrained top-level mode to enforce mut exclusivity and unused-pure checks!
        BodyAnalyzer top = new BodyAnalyzer(
                file, pass.fileScope, List.of(), false,
                new boolean[0], new boolean[0], new boolean[0], false, false,
                ParsedPrefix.EMPTY, Set.of(), null, true);
        for (BabbleParser.ExpressionContext expr : file.expression()) {
            top.eval(expr);
        }
        verifyStaticBlocks(file, Set.of());
    }

    private void verifyStaticBlocks(ParseTree tree, Set<Effect> activeEnclosingConstraints) {
        Set<Effect> nextConstraints = activeEnclosingConstraints;
        if (tree instanceof BabbleParser.FunctionLiteralContext fn) {
            ParsedPrefix p = parseFunctionPrefix(fn, false);
            if (p.axiom()) {
                return;
            }
            nextConstraints = p.effects();
        }
        if (tree instanceof BabbleParser.ClassExpressionContext cls && cls.staticBlock != null) {
            checkConstrainedBlock(cls.staticBlock, pass.scopeNodes.get(cls.staticBlock), nextConstraints);
        } else if (tree instanceof BabbleParser.InterfaceExpressionContext iface && iface.staticBlock != null) {
            checkConstrainedBlock(iface.staticBlock, pass.scopeNodes.get(iface.staticBlock), nextConstraints);
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            verifyStaticBlocks(tree.getChild(i), nextConstraints);
        }
    }

    private void checkConstrainedBlock(BabbleParser.BlockContext block, ScopeNode scope, Set<Effect> constraints) {
        ParsedPrefix req = new ParsedPrefix(
                constraints.isEmpty() ? EnumSet.noneOf(Effect.class) : EnumSet.copyOf(constraints),
                false, false, false, false, false, false, false, !constraints.isEmpty());
        BodyAnalyzer ba = new BodyAnalyzer(
                block, scope, List.of(), false,
                new boolean[0], new boolean[0], new boolean[0], false, false,
                req, Set.of(), null, true);
        ba.evalBlock(block, false, true);
        if (constraints.contains(Effect.PURE) && !ba.hermetic) {
            checker.effectDiagnostic("static block inside pure function violates pure guarantee", block);
        } else if (constraints.contains(Effect.HERMETIC) && !ba.hermetic) {
            checker.effectDiagnostic("static block inside hermetic function violates hermetic guarantee", block);
        } else if (constraints.contains(Effect.DETERMINISTIC) && !ba.deterministic) {
            checker.effectDiagnostic("static block inside deterministic function violates deterministic guarantee", block);
        }
    }

    private void recordAllCallEffects(ParseTree tree) {
        if (tree instanceof BabbleParser.CallContext call) {
            BodyAnalyzer probe = new BodyAnalyzer(
                    call, checker.occurrenceScopeOf(call), List.of(), false,
                    new boolean[0], new boolean[0], new boolean[0], false, false,
                    ParsedPrefix.EMPTY, Set.of(), null, false);
            probe.eval(call);
            EnumSet<Effect> effs = EnumSet.noneOf(Effect.class);
            if (probe.hermetic) {
                effs.add(Effect.PURE);
                effs.add(Effect.HERMETIC);
            }
            if (probe.deterministic) {
                effs.add(Effect.DETERMINISTIC);
            }
            if (probe.nothrow) {
                effs.add(Effect.NOTHROW);
            }
            pass.analysis.setCallEffects(call, EffectSet.ofInferred(effs, false, false, probe.uniqueResult, probe.terminating));
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            recordAllCallEffects(tree.getChild(i));
        }
    }

    // ================================================================ 9. Algebraic Validation & Composition

    private void validateAlgebraicSignature(Set<Effect> effects, List<Type.Parameter> params, Type result,
                                            boolean mutThis, boolean axiomatic, ParserRuleContext at) {
        if (effects.isEmpty()) {
            return;
        }
        boolean anyMutParam = params.stream().anyMatch(Type.Parameter::mut);
        boolean hasMut = mutThis || anyMutParam;
        boolean isPure = effects.contains(Effect.PURE);

        if (effects.contains(Effect.ASSOCIATIVE)) {
            if (!isPure || hasMut || params.size() != 2
                    || result == Type.NULL || result == Type.NOTHING
                    || !sameDomainType(params.get(0).type(), params.get(1).type())
                    || !sameDomainType(params.get(0).type(), result)) {
                checker.effectDiagnostic("associative requires a pure binary (T, T) : T signature", at);
            }
        }
        if (effects.contains(Effect.COMMUTATIVE)) {
            if (!hasMut) {
                if (!isPure || params.size() != 2 || !sameDomainType(params.get(0).type(), params.get(1).type())) {
                    checker.effectDiagnostic("commutative without mut requires a pure binary (T, T) : R signature", at);
                }
            } else if (mutThis ? params.isEmpty() : params.size() < 2) {
                checker.effectDiagnostic("mut commutative requires a value parameter on the mutated target", at);
            }
        }
        if (effects.contains(Effect.IDEMPOTENT)) {
            if (!hasMut && !(axiomatic && !isPure)) {
                if (!isPure || params.size() != 1 || result == Type.NULL || !sameDomainType(params.get(0).type(), result)) {
                    checker.effectDiagnostic("idempotent without mut requires a pure unary (T) : T signature or an effectful axiom", at);
                }
            }
        }
        if (effects.contains(Effect.MONOTONE)) {
            if (hasMut || params.isEmpty() || !isOrderedType(params.get(0).type()) || !isOrderedType(result)) {
                checker.effectDiagnostic("monotone requires ordered parameter and return types without mut", at);
            }
        }
        if (effects.contains(Effect.INJECTIVE)) {
            if (hasMut || params.isEmpty() || result == Type.NULL || result == Type.NOTHING || result == Type.BOOL && !isBoolType(params.get(0).type())) {
                if (result == Type.NULL || result == Type.NOTHING || hasMut || params.isEmpty()) {
                    checker.effectDiagnostic("injective requires at least one parameter, no mut, and a non-null return type", at);
                }
            }
        }
    }

    private boolean sameDomainType(Type a, Type b) {
        if (a == Type.ERROR || b == Type.ERROR) {
            return true;
        }
        return a.equals(b) || (checker.assignable(a, b) && checker.assignable(b, a));
    }

    private static boolean isBoolType(Type t) {
        return t == Type.BOOL;
    }

    static boolean isOrderedType(Type t) {
        if (t == Type.ERROR) {
            return true;
        }
        if (t == Type.INT || t == Type.DOUBLE || t == Type.BOOL || t == Type.STRING) {
            return true;
        }
        if (t instanceof Type.JavaInstance ji) {
            return Comparable.class.isAssignableFrom(ji.type());
        }
        return false;
    }

    private void inferAlgebraicProperties(BabbleParser.FunctionLiteralContext fn,
                                          EnumSet<Effect> effs,
                                          boolean[] mutParams,
                                          boolean mutThis) {
        List<BabbleParser.FnParamContext> paramDecls = fn.fnParameters().fnParam();
        Type fnRaw = checker.rawTypeOf(fn);
        if (!(fnRaw instanceof Type.Function tf)) {
            return;
        }
        List<Type.Parameter> params = tf.parameters();
        Type result = tf.result();
        BabbleParser.ExpressionContext single = singleBodyExpression(fn.functionBlock);

        boolean anyMut = mutThis || anyTrue(mutParams);
        if (!anyMut && effs.contains(Effect.PURE) && effs.contains(Effect.DETERMINISTIC) && single != null) {
            // 1. Compositional monotone & injective in parameter 0 (when other parameters are untouched constants).
            if (!paramDecls.isEmpty()) {
                BabbleParser.FnParamContext p0 = paramDecls.get(0);
                RelProps rel = relationalProps(single, p0);
                if (rel.monotoneSign == 1 && isOrderedType(params.get(0).type()) && isOrderedType(result)) {
                    effs.add(Effect.MONOTONE);
                }
                if (rel.injective && result != Type.NULL && result != Type.NOTHING) {
                    effs.add(Effect.INJECTIVE);
                }
            }
            // 2. Binary single-op wrapper for commutative & associative.
            if (paramDecls.size() == 2 && sameDomainType(params.get(0).type(), params.get(1).type())) {
                BabbleParser.FnParamContext p0 = paramDecls.get(0);
                BabbleParser.FnParamContext p1 = paramDecls.get(1);
                WrapperProps wp = binaryWrapperProps(single, p0, p1);
                if (wp.commutative) {
                    effs.add(Effect.COMMUTATIVE);
                }
                if (wp.associative && sameDomainType(params.get(0).type(), result)
                        && result != Type.NULL && result != Type.NOTHING) {
                    effs.add(Effect.ASSOCIATIVE);
                }
            }
            // 3. Unary single-op wrapper for idempotent.
            if (paramDecls.size() == 1 && sameDomainType(params.get(0).type(), result) && result != Type.NULL) {
                if (isUnaryIdempotentWrapper(single, paramDecls.get(0))) {
                    effs.add(Effect.IDEMPOTENT);
                }
            }
        } else if (anyMut && effs.contains(Effect.HERMETIC) && single != null) {
            // Effect-form single-op wrapper or invariant field overwrite!
            if (isMutIdempotentBody(fn, single, paramDecls, mutParams, mutThis)) {
                effs.add(Effect.IDEMPOTENT);
            }
            if (isMutCommutativeBody(fn, single, paramDecls, mutParams, mutThis)) {
                effs.add(Effect.COMMUTATIVE);
            }
        }
    }

    private record RelProps(int monotoneSign, boolean injective, boolean usesTarget) {
        static final RelProps NONE = new RelProps(0, false, true);
        static final RelProps CONST = new RelProps(0, false, false);
    }

    private record WrapperProps(boolean commutative, boolean associative) {
        static final WrapperProps NONE = new WrapperProps(false, false);
    }

    private static BabbleParser.ExpressionContext singleBodyExpression(BabbleParser.BlockContext block) {
        if (block == null || block.expression().size() != 1 || !block.effectDeclaration().isEmpty()) {
            return null;
        }
        BabbleParser.ExpressionContext e = block.expression(0);
        while (true) {
            if (e instanceof BabbleParser.ReturnExpressionContext ret && ret.expression() != null) {
                e = ret.expression();
            } else if (e instanceof BabbleParser.BlockExpressionContext be
                    && be.block().expression().size() == 1 && be.block().effectDeclaration().isEmpty()) {
                e = be.block().expression(0);
            } else {
                return e;
            }
        }
    }

    private static BabbleParser.ExpressionContext unwrapParens(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext be
                && be.block().expression().size() == 1 && be.block().effectDeclaration().isEmpty()) {
            expr = be.block().expression(0);
        }
        return expr;
    }

    private RelProps relationalProps(BabbleParser.ExpressionContext raw, BabbleParser.FnParamContext targetParam) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        if (!referencesDecl(expr, targetParam)) {
            return RelProps.CONST;
        }
        if (expr instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            Object resolved = checker.resolveNameForEffects(sel.ID().getText(), checker.occurrenceScopeOf(sel));
            if (resolved == targetParam) {
                Type t = checker.rawTypeOf(sel);
                return new RelProps(isOrderedType(t) ? 1 : 0, true, true);
            }
            return RelProps.CONST;
        }
        if (expr instanceof BabbleParser.NegateContext neg) {
            RelProps sub = relationalProps(neg.operand, targetParam);
            if (!sub.usesTarget) {
                return RelProps.CONST;
            }
            Type resType = checker.rawTypeOf(neg);
            if (resType == Type.INT) {
                // Wrapping 32-bit negation is an involution (injective), NOT monotone!
                return new RelProps(0, sub.injective, true);
            }
            if (resType == Type.DOUBLE) {
                return new RelProps(-sub.monotoneSign, sub.injective, true);
            }
            return RelProps.NONE;
        }
        if (expr instanceof BabbleParser.BinaryOpContext bin) {
            String op = bin.op.getText();
            RelProps l = relationalProps(bin.left, targetParam);
            RelProps r = relationalProps(bin.right, targetParam);
            Type resType = checker.rawTypeOf(bin);
            if (resType == Type.INT) {
                // Wrapping 32-bit int arithmetic is NEVER monotone (§3.2, §4.6)!
                if (l.usesTarget && !r.usesTarget) {
                    boolean inj = switch (op) {
                        case "+", "-" -> l.injective;
                        case "*" -> l.injective && isOddIntConstant(bin.right);
                        default -> false;
                    };
                    return new RelProps(0, inj, true);
                }
                if (!l.usesTarget && r.usesTarget) {
                    boolean inj = switch (op) {
                        case "+", "-" -> r.injective;
                        case "*" -> r.injective && isOddIntConstant(bin.left);
                        default -> false;
                    };
                    return new RelProps(0, inj, true);
                }
                return RelProps.NONE;
            }
            if (resType == Type.DOUBLE) {
                // IEEE-754 double arithmetic: monotone on +, -, and * (k >= 0), NOT injective (§4.6).
                if ("+".equals(op)) {
                    int sign = combineAddSigns(l, r);
                    return new RelProps(sign, false, true);
                }
                if ("-".equals(op)) {
                    RelProps negR = r.usesTarget ? new RelProps(-r.monotoneSign, false, true) : RelProps.CONST;
                    int sign = combineAddSigns(l, negR);
                    return new RelProps(sign, false, true);
                }
                if ("*".equals(op)) {
                    if (l.usesTarget && !r.usesTarget) {
                        Double k = doubleConstant(bin.right);
                        int sign = k == null ? 0 : (k >= 0.0 ? l.monotoneSign : -l.monotoneSign);
                        return new RelProps(sign, false, true);
                    }
                    if (!l.usesTarget && r.usesTarget) {
                        Double k = doubleConstant(bin.left);
                        int sign = k == null ? 0 : (k >= 0.0 ? r.monotoneSign : -r.monotoneSign);
                        return new RelProps(sign, false, true);
                    }
                }
                return RelProps.NONE;
            }
            if (resType == Type.STRING && "+".equals(op)) {
                Type lt = checker.rawTypeOf(bin.left);
                Type rt = checker.rawTypeOf(bin.right);
                if (l.usesTarget && !r.usesTarget) {
                    boolean inj = l.injective && isLosslessToStringType(lt);
                    return new RelProps(0, inj, true);
                }
                if (!l.usesTarget && r.usesTarget) {
                    boolean inj = r.injective && isLosslessToStringType(rt);
                    return new RelProps(0, inj, true);
                }
                return RelProps.NONE;
            }
            return RelProps.NONE;
        }
        if (expr instanceof BabbleParser.CallContext call) {
            List<BabbleParser.CallParameterContext> args = call.callParameters().callParameter();
            if (args.isEmpty()) {
                return RelProps.NONE;
            }
            for (int i = 1; i < args.size(); i++) {
                if (referencesDecl(args.get(i).expression(), targetParam)) {
                    return RelProps.NONE;
                }
            }
            if (call.expression() instanceof BabbleParser.SelectorContext sel && sel.expression() != null
                    && referencesDecl(sel.expression(), targetParam)) {
                return RelProps.NONE;
            }
            RelProps arg0 = relationalProps(args.get(0).expression(), targetParam);
            if (!arg0.usesTarget) {
                return RelProps.CONST;
            }
            EffectSet calleeEffects = calleeIntrinsicOrResolvedEffects(call);
            if (calleeEffects == null || !calleeEffects.isPure() || !calleeEffects.isDeterministic()) {
                return RelProps.NONE;
            }
            int mono = (calleeEffects.has(Effect.MONOTONE) && arg0.monotoneSign == 1) ? 1 : 0;
            boolean inj = calleeEffects.has(Effect.INJECTIVE) && arg0.injective;
            return new RelProps(mono, inj, true);
        }
        if (expr instanceof BabbleParser.AsExpressionContext asExpr) {
            Type src = checker.rawTypeOf(asExpr.left);
            Type dst = checker.rawTypeOf(asExpr);
            if (src == Type.INT && dst == Type.DOUBLE) {
                return relationalProps(asExpr.left, targetParam);
            }
        }
        return RelProps.NONE;
    }

    private static int combineAddSigns(RelProps l, RelProps r) {
        if (l.usesTarget && !r.usesTarget) {
            return l.monotoneSign;
        }
        if (!l.usesTarget && r.usesTarget) {
            return r.monotoneSign;
        }
        if (l.usesTarget && r.usesTarget && l.monotoneSign != 0 && l.monotoneSign == r.monotoneSign) {
            return l.monotoneSign;
        }
        return 0;
    }

    private static boolean isLosslessToStringType(Type type) {
        return type == Type.STRING || type == Type.INT || type == Type.BOOL;
    }

    private static boolean isOddIntConstant(BabbleParser.ExpressionContext raw) {
        BabbleParser.ExpressionContext e = unwrapParens(raw);
        if (e instanceof BabbleParser.NegateContext neg) {
            return isOddIntConstant(neg.operand);
        }
        if (e instanceof BabbleParser.IntegerContext ic) {
            try {
                int v = Integer.parseInt(ic.getText());
                return (v & 1) != 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private static Double doubleConstant(BabbleParser.ExpressionContext raw) {
        BabbleParser.ExpressionContext e = unwrapParens(raw);
        if (e instanceof BabbleParser.NegateContext neg) {
            Double sub = doubleConstant(neg.operand);
            return sub != null ? -sub : null;
        }
        if (e instanceof BabbleParser.IntegerContext ic) {
            try {
                return (double) Integer.parseInt(ic.getText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (e instanceof BabbleParser.DoubleContext dc) {
            try {
                double d = Double.parseDouble(dc.getText());
                return Double.isNaN(d) ? null : d;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private WrapperProps binaryWrapperProps(BabbleParser.ExpressionContext raw,
                                            BabbleParser.FnParamContext p0,
                                            BabbleParser.FnParamContext p1) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        if (expr instanceof BabbleParser.BinaryOpContext bin) {
            int order = matchBinaryParams(bin.left, bin.right, p0, p1);
            if (order == 0) {
                return WrapperProps.NONE;
            }
            String op = bin.op.getText();
            Type res = checker.rawTypeOf(bin);
            Type lt = checker.rawTypeOf(bin.left);
            Type rt = checker.rawTypeOf(bin.right);
            if ("+".equals(op)) {
                if (res == Type.INT) {
                    return new WrapperProps(true, true);
                }
                if (res == Type.DOUBLE) {
                    return new WrapperProps(true, false);
                }
                if (lt == Type.STRING && rt == Type.STRING) {
                    return new WrapperProps(false, order == 1);
                }
            } else if ("*".equals(op)) {
                if (res == Type.INT) {
                    return new WrapperProps(true, true);
                }
                if (res == Type.DOUBLE) {
                    return new WrapperProps(true, false);
                }
            } else if ("==".equals(op) || "!=".equals(op) || "<>".equals(op)) {
                return new WrapperProps(true, false);
            }
            return WrapperProps.NONE;
        }
        if (expr instanceof BabbleParser.CallContext call) {
            List<BabbleParser.CallParameterContext> args = call.callParameters().callParameter();
            if (args.size() != 2 || ScopeAnalysis.callParamName(args.get(0)) != null || ScopeAnalysis.callParamName(args.get(1)) != null) {
                return WrapperProps.NONE;
            }
            int order = matchBinaryParams(args.get(0).expression(), args.get(1).expression(), p0, p1);
            if (order == 0) {
                return WrapperProps.NONE;
            }
            EffectSet effs = calleeIntrinsicOrResolvedEffects(call);
            if (effs == null || !effs.isPure()) {
                return WrapperProps.NONE;
            }
            return new WrapperProps(effs.has(Effect.COMMUTATIVE), order == 1 && effs.has(Effect.ASSOCIATIVE));
        }
        return WrapperProps.NONE;
    }

    private int matchBinaryParams(BabbleParser.ExpressionContext a, BabbleParser.ExpressionContext b,
                                  BabbleParser.FnParamContext p0,
                                  BabbleParser.FnParamContext p1) {
        Object ra = resolvePlainParam(unwrapParens(a));
        Object rb = resolvePlainParam(unwrapParens(b));
        if (ra == p0 && rb == p1) {
            return 1;
        }
        if (ra == p1 && rb == p0) {
            return -1;
        }
        return 0;
    }

    private boolean isUnaryIdempotentWrapper(BabbleParser.ExpressionContext raw,
                                             BabbleParser.FnParamContext p0) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        if (resolvePlainParam(expr) == p0) {
            // Identity function `(x) -> (x)` is idempotent!
            return true;
        }
        if (expr instanceof BabbleParser.CallContext call) {
            List<BabbleParser.CallParameterContext> args = call.callParameters().callParameter();
            if (args.size() == 1 && ScopeAnalysis.callParamName(args.get(0)) == null && resolvePlainParam(unwrapParens(args.get(0).expression())) == p0) {
                EffectSet effs = calleeIntrinsicOrResolvedEffects(call);
                return effs != null && effs.isPure() && effs.has(Effect.IDEMPOTENT);
            }
            if (args.isEmpty() && call.expression() instanceof BabbleParser.SelectorContext sel && sel.expression() != null
                    && resolvePlainParam(unwrapParens(sel.expression())) == p0) {
                EffectSet effs = calleeIntrinsicOrResolvedEffects(call);
                return effs != null && effs.isPure() && effs.has(Effect.IDEMPOTENT);
            }
        }
        return false;
    }

    private boolean isMutIdempotentBody(BabbleParser.FunctionLiteralContext fn,
                                        BabbleParser.ExpressionContext raw,
                                        List<BabbleParser.FnParamContext> params,
                                        boolean[] mutParams,
                                        boolean mutThis) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        // 1. Direct overwrite `this.field = val` or `mutParam.field = val` where `val` does not read the overwritten field!
        if (expr instanceof BabbleParser.AssignExpressionContext assign && "=".equals(assign.op.getText()) && ScopeAnalysis.assignReceiver(assign) != null) {
            BabbleParser.ExpressionContext ns = unwrapParens(ScopeAnalysis.assignReceiver(assign));
            if ((mutThis && ns instanceof BabbleParser.ThisExpressionContext)
                    || (resolvePlainParam(ns) instanceof BabbleParser.FnParamContext p
                    && params.indexOf(p) >= 0 && mutParams[params.indexOf(p)])) {
                if (!readsField(assign.value, ScopeAnalysis.assignName(assign))) {
                    return true;
                }
            }
        }
        // 2. Forwarding to an `idempotent` mut method!
        if (expr instanceof BabbleParser.CallContext call) {
            EffectSet effs = calleeIntrinsicOrResolvedEffects(call);
            return effs != null && effs.has(Effect.IDEMPOTENT);
        }
        return false;
    }

    private boolean isMutCommutativeBody(BabbleParser.FunctionLiteralContext fn,
                                         BabbleParser.ExpressionContext raw,
                                         List<BabbleParser.FnParamContext> params,
                                         boolean[] mutParams,
                                         boolean mutThis) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        // `this.x += delta` on int (`+` is associative & commutative!) or forwarding to a `mut commutative` method!
        if (expr instanceof BabbleParser.AssignExpressionContext assign && ScopeAnalysis.assignReceiver(assign) != null) {
            BabbleParser.ExpressionContext recv = ScopeAnalysis.assignReceiver(assign);
            String op = assign.op.getText();
            Type targetType = checker.rawTypeOf(assign);
            if ("+=".equals(op) && targetType == Type.INT && params.size() == 1
                    && resolvePlainParam(unwrapParens(assign.value)) == params.get(0)) {
                return true;
            }
            if ("=".equals(op) && targetType == Type.INT && params.size() == 1
                    && assign.value instanceof BabbleParser.BinaryOpContext bin && "+".equals(bin.op.getText())) {
                String assignedName = ScopeAnalysis.assignName(assign);
                if ((sameTargetField(recv, assignedName, bin.left)
                        && resolvePlainParam(unwrapParens(bin.right)) == params.get(0))
                        || (sameTargetField(recv, assignedName, bin.right)
                        && resolvePlainParam(unwrapParens(bin.left)) == params.get(0))) {
                    return true;
                }
            }
        }
        if (expr instanceof BabbleParser.CallContext call) {
            EffectSet effs = calleeIntrinsicOrResolvedEffects(call);
            return effs != null && effs.has(Effect.COMMUTATIVE);
        }
        return false;
    }

    private boolean sameTargetField(BabbleParser.ExpressionContext ns, String field, BabbleParser.ExpressionContext expr) {
        expr = unwrapParens(expr);
        if (!(expr instanceof BabbleParser.SelectorContext sel) || sel.expression() == null || !field.equals(ScopeAnalysis.selectorName(sel))) {
            return false;
        }
        BabbleParser.ExpressionContext r1 = unwrapParens(ns);
        BabbleParser.ExpressionContext r2 = unwrapParens(sel.expression());
        return r1 instanceof BabbleParser.ThisExpressionContext && r2 instanceof BabbleParser.ThisExpressionContext;
    }

    private static boolean readsField(ParseTree tree, String fieldName) {
        if (tree instanceof BabbleParser.SelectorContext sel && sel.expression() != null
                && fieldName.equals(ScopeAnalysis.selectorName(sel))) {
            return true;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (readsField(tree.getChild(i), fieldName)) {
                return true;
            }
        }
        return false;
    }

    private Object resolvePlainParam(BabbleParser.ExpressionContext expr) {
        if (expr instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            Object r = checker.resolveNameForEffects(sel.ID().getText(), checker.occurrenceScopeOf(sel));
            if (r instanceof ParserRuleContext prc && ScopeAnalysis.isParamDecl(prc)) {
                return r;
            }
        }
        return null;
    }

    private boolean referencesDecl(ParseTree tree, ParserRuleContext targetDecl) {
        if (tree instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            return checker.resolveNameForEffects(sel.ID().getText(), checker.occurrenceScopeOf(sel)) == targetDecl;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (referencesDecl(tree.getChild(i), targetDecl)) {
                return true;
            }
        }
        return false;
    }

    private EffectSet calleeIntrinsicOrResolvedEffects(BabbleParser.CallContext call) {
        Method rm = checker.resolvedJavaMethodOf(call);
        if (rm != null) {
            return resolveJavaMethodProfile(rm.getDeclaringClass(), rm.getName(), rm).effects();
        }
        Constructor<?> rc = checker.resolvedJavaConstructorOf(call);
        if (rc != null) {
            return resolveJavaConstructorProfile(rc.getDeclaringClass(), rc).effects();
        }
        Type callee = checker.rawTypeOf(call.expression());
        if (callee instanceof Type.JavaMethod jm) {
            return resolveJavaMethodProfile(jm.owner(), jm.name(), null).effects();
        }
        if (callee instanceof Type.JavaClassValue jcv) {
            return resolveJavaConstructorProfile(jcv.type(), null).effects();
        }
        if (callee instanceof Type.ClassValue cv) {
            CallableSummary cs = constructorSummary(cv.node());
            if (cs != null) {
                boolean isVal = cv.node().kind == ClassShape.Kind.VALUE_CLASS;
                EnumSet<Effect> set = EffectSet.copySet(cs.effects().effects());
                if (isVal && set.contains(Effect.PURE) && set.contains(Effect.DETERMINISTIC) && !cv.node().parameters.isEmpty()) {
                    set.add(Effect.INJECTIVE);
                }
                return EffectSet.ofInferred(set, false, false);
            }
        }
        if (callee instanceof Type.Function fn) {
            BabbleParser.FunctionLiteralContext lit = checker.literalOf(fn);
            if (lit != null && summaries.containsKey(lit)) {
                return summaries.get(lit).effects();
            }
            return fn.effects();
        }
        return null;
    }

    // ================================================================ 10. Deep Immutability & Value Protocol Helpers

    boolean isDeeplyImmutable(Type type) {
        return isDeeplyImmutable(type, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private boolean isDeeplyImmutable(Type type, Set<TypeNode> visiting) {
        if (type == null || type == Type.ERROR || type == Type.NOTHING) {
            return true;
        }
        if (Type.isCoreScalar(type) || type == Type.NULL) {
            return true;
        }
        if (type instanceof Type.Frozen) {
            return true;
        }
        if (type instanceof Type.Union u) {
            return u.members().stream().allMatch(m -> isDeeplyImmutable(m, visiting));
        }
        if (type instanceof Type.Instance inst && inst.node().kind == ClassShape.Kind.OBJECT) {
            for (TypeNode.Member m : inst.node().members.values()) {
                if (!m.hasFunction) {
                    return false;
                }
            }
            return true;
        }
        if (type instanceof Type.Instance inst && inst.node().kind == ClassShape.Kind.VALUE_CLASS) {
            if (!visiting.add(inst.node())) {
                return true;
            }
            for (TypeNode.Member m : inst.node().members.values()) {
                if (!m.hasFunction) {
                    Type mt = checker.instanceMemberTypeForEffects(inst, m);
                    if (mt == null || !isDeeplyImmutable(mt, visiting)) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    boolean isImmutableConstantValue(Type type) {
        if (isDeeplyImmutable(type)) {
            return true;
        }
        if (type instanceof Type.Function || type instanceof Type.Overloaded || type instanceof Type.ClassValue
                || type instanceof Type.JavaClassValue || type instanceof Type.JavaPackage
                || type instanceof Type.JavaMethod || type instanceof Type.Module) {
            return true;
        }
        return false;
    }

    /** Profile of converting {@code type} to string in {@code string + x} (bip007_effects.md §4.3, §4.6). */
    private EffectSet toStringProfile(Type type) {
        if (type instanceof Type.Frozen f) {
            return toStringProfile(f.of());
        }
        if (type == null || type == Type.ERROR) {
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
        }
        if (Type.isCoreScalar(type) || type == Type.NULL) {
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
        }
        if (type instanceof Type.Union u) {
            EffectSet acc = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
            for (Type m : u.members()) {
                acc = acc.intersect(toStringProfile(m));
            }
            return acc;
        }
        if (type instanceof Type.Instance inst) {
            TypeNode node = inst.node();
            TypeNode.Member custom = checker.findMemberOnNode(node, "toString");
            if (custom != null && custom.hasFunction) {
                CallableSummary cs = custom.function != null ? summaries.get(custom.function) : null;
                if (cs != null) {
                    return cs.effects();
                }
                Type mt = checker.memberTypeForEffects(node, custom);
                if (mt instanceof Type.Function tf) {
                    return tf.effects();
                }
            }
            if (node.kind == ClassShape.Kind.VALUE_CLASS) {
                // Structural toString formats every field!
                EffectSet acc = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
                for (TypeNode.Member m : node.members.values()) {
                    if (!m.hasFunction) {
                        Type mt = checker.instanceMemberTypeForEffects(inst, m);
                        if (mt == null) {
                            return EffectSet.EMPTY;
                        }
                        acc = acc.intersect(toStringProfile(mt));
                    }
                }
                return acc;
            }
            // Non-value Babble instance default toString uses identity hash -> pure only (not deterministic)!
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.NOTHROW), false, true, false, true);
        }
        if (type instanceof Type.JavaInstance ji) {
            return resolveJavaMethodProfile(ji.type(), "toString", lookupPublicMethod(ji.type(), "toString")).effects();
        }
        if (type instanceof Type.Array arr) {
            return toStringProfile(arr.element());
        }
        return EffectSet.EMPTY;
    }

    private static Method lookupPublicMethod(Class<?> cls, String name, Class<?>... params) {
        if (cls == null) {
            return null;
        }
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private EffectSet equalityProfile(Type left, Type right) {
        return singleEqualityProfile(left).intersect(singleEqualityProfile(right));
    }

    private EffectSet singleEqualityProfile(Type type) {
        if (type instanceof Type.Frozen f) {
            return singleEqualityProfile(f.of());
        }
        if (type == null || type == Type.ERROR || Type.isCoreScalar(type) || type == Type.NULL
                || type instanceof Type.Function || type instanceof Type.ClassValue
                || type instanceof Type.JavaClassValue) {
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
        }
        if (type instanceof Type.Union u) {
            EffectSet acc = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
            for (Type m : u.members()) {
                acc = acc.intersect(singleEqualityProfile(m));
            }
            return acc;
        }
        if (type instanceof Type.Instance inst) {
            if (inst.node().kind == ClassShape.Kind.VALUE_CLASS) {
                EffectSet acc = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
                for (TypeNode.Member m : inst.node().members.values()) {
                    if (!m.hasFunction) {
                        Type mt = checker.instanceMemberTypeForEffects(inst, m);
                        if (mt != null) {
                            acc = acc.intersect(singleEqualityProfile(mt));
                        }
                    }
                }
                return acc;
            }
            // Regular Babble instance uses `==` reference identity (pure deterministic nothrow)!
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
        }
        if (type instanceof Type.JavaInstance ji) {
            return resolveJavaMethodProfile(ji.type(), "equals", lookupPublicMethod(ji.type(), "equals", Object.class)).effects();
        }
        if (type instanceof Type.Array arr) {
            return singleEqualityProfile(arr.element());
        }
        return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, true, false, true);
    }

    private EffectSet comparisonProfile(Type left, Type right) {
        if (left instanceof Type.Union lu) {
            EffectSet acc = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
            for (Type m : lu.members()) {
                acc = acc.intersect(comparisonProfile(m, right));
            }
            return acc;
        }
        if (right instanceof Type.Union ru) {
            EffectSet acc = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
            for (Type m : ru.members()) {
                acc = acc.intersect(comparisonProfile(left, m));
            }
            return acc;
        }
        if (isPrimitiveOrString(left) && isPrimitiveOrString(right)) {
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
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

    private static boolean isPrimitiveOrString(Type t) {
        return Type.isCoreScalar(t);
    }

    // ================================================================ 11. Intra-Procedural Flow & Alias Analyzer

    private final class BodyAnalyzer {
        private final ParserRuleContext owner;
        private final ScopeNode ownerScope;
        private final List<? extends ParserRuleContext> params;
        private final Map<ParserRuleContext, Integer> paramIndex = new IdentityHashMap<>();
        private final boolean inferMut;
        final boolean[] mutParams;
        final boolean[] borrowParams;
        final boolean[] uniqueParams;
        boolean mutThis;
        boolean borrowThis;
        boolean constructingSelf;
        private final ParsedPrefix declaredPrefix;
        private final Set<BabbleParser.FunctionLiteralContext> frameChain;
        private final Map<ParserRuleContext, Type.Function> specializedParams;
        private final boolean emitDiagnostics;

        boolean hermetic = true;
        boolean deterministic = true;
        boolean nothrow = true;
        boolean uniqueResult = true;
        boolean terminating;
        boolean deepMutThis = false;
        final BitSet returnParams = new BitSet();
        boolean returnThis = false;
        boolean returnExternal = false;
        final BitSet ctorStoredParams = new BitSet();
        private boolean stmtHasSideEffect = false;
        private boolean stmtHasError = false;
        private ParserRuleContext discardedExpr = null;

        final Set<BabbleParser.FunctionLiteralContext> frameReadOwners = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<BabbleParser.FunctionLiteralContext> frameWriteOwners = Collections.newSetFromMap(new IdentityHashMap<>());

        private Map<ParserRuleContext, Origin> env = new IdentityHashMap<>();
        private final Set<String> reportedMessages = new HashSet<>();
        private final List<Origin> activeForIterables = new ArrayList<>();
        private final Deque<Origin[]> loopBreakOrigins = new ArrayDeque<>();
        private final Set<Integer> forwardedParams;
        /**
         * Callbacks of an enclosing function the owner names in its calling clause (BIP017-D5),
         * tracked as pseudo-parameters with the bits after the owner's own parameters, so that
         * returning or storing one is an escape (BIP017-D6). Never published in summaries.
         */
        private final Map<ParserRuleContext, Integer> pseudoForwarded = new IdentityHashMap<>();
        private final Map<ParserRuleContext, Origin> boundMethodReceivers = new IdentityHashMap<>();
        /** Parameters and receiver from which this callable reads mutable state (BIP019 §4). */
        final boolean[] readParams;
        boolean readsThis = false;

        BodyAnalyzer(ParserRuleContext owner,
                     ScopeNode ownerScope,
                     List<? extends ParserRuleContext> params,
                     boolean inferMut,
                     boolean[] mutParams,
                     boolean[] borrowParams,
                     boolean[] uniqueParams,
                     boolean mutThis,
                     boolean borrowThis,
                     ParsedPrefix declaredPrefix,
                     Set<BabbleParser.FunctionLiteralContext> frameChain,
                     Map<ParserRuleContext, Type.Function> specializedParams,
                     boolean emitDiagnostics) {
            this.owner = owner;
            this.ownerScope = ownerScope;
            this.params = params;
            this.inferMut = inferMut;
            this.mutParams = mutParams;
            this.borrowParams = borrowParams;
            this.uniqueParams = uniqueParams;
            this.readParams = new boolean[params.size()];
            this.mutThis = mutThis;
            this.borrowThis = borrowThis;
            this.declaredPrefix = declaredPrefix;
            this.frameChain = frameChain;
            this.specializedParams = specializedParams;
            this.emitDiagnostics = emitDiagnostics;
            this.terminating = !cyclicCallables.contains(owner);
            this.forwardedParams = owner instanceof BabbleParser.FunctionLiteralContext fn
                    ? resolveForwardedParamIndices(fn) : Set.of();
            if (owner instanceof BabbleParser.FunctionLiteralContext fn && capturesCallbacks(fn)) {
                for (org.antlr.v4.runtime.tree.TerminalNode id : fn.effectPrefix().callingClause().ID()) {
                    ParserRuleContext captured = TypeAnnotations.enclosingParameter(fn.effectPrefix(), id.getText());
                    if (captured instanceof BabbleParser.FnParamContext param && capturesCallback(fn, param)
                            && !pseudoForwarded.containsKey(param)) {
                        pseudoForwarded.put(param, params.size() + pseudoForwarded.size());
                    }
                }
            }

            for (int i = 0; i < params.size(); i++) {
                ParserRuleContext p = params.get(i);
                paramIndex.put(p, i);
                Type pType = checker.typeOfDeclarationForEffects(p);
                boolean isUnique = i < uniqueParams.length && uniqueParams[i];
                if (isUnique) {
                    boolean borrowed = i < borrowParams.length && borrowParams[i];
                    env.put(p, Origin.ofUniqueParam(i, p, isDeeplyImmutable(pType), borrowed));
                } else {
                    env.put(p, Origin.ofParam(i, p, isDeeplyImmutable(pType)));
                }
            }
        }

        /**
         * Records a read of mutable state from {@code orig} (BIP019 §4.1): deterministic if frozen,
         * deeply immutable, or exclusively owned; composed into {@code readParams}/{@code readsThis}
         * when reached via a parameter or {@code this}; volatile where provenance runs out.
         */
        private void recordTargetRead(Origin orig, Type targetType) {
            if (targetType == Type.ERROR || targetType == Type.NOTHING || targetType == Type.NULL) {
                return;
            }
            if (containsFrozen(targetType) || isDeeplyImmutable(targetType)) {
                return;
            }
            if (orig == null || orig == Origin.IMMUTABLE) {
                return;
            }
            if ((orig.unique || orig.localFresh) && !orig.external && orig.params.isEmpty() && !orig.aliasesThis) {
                return;
            }
            for (int p = orig.params.nextSetBit(0); p >= 0; p = orig.params.nextSetBit(p + 1)) {
                if (p < readParams.length) {
                    readParams[p] = true;
                }
            }
            if (orig.aliasesThis && !constructingSelf) {
                readsThis = true;
            }
            if (orig.external) {
                deterministic = false;
            }
        }

        private void demoteUniqueOwner(Object ownerKey, Origin taint) {
            if (ownerKey instanceof ParserRuleContext decl) {
                Origin cur = env.get(decl);
                if (cur != null && (cur.unique || cur.localFresh)) {
                    env.put(decl, cur.demoteAlias(taint));
                }
            }
        }

        private void absorbElementIntoLocalContainer(Object ownerKey, Origin taint) {
            if (ownerKey instanceof ParserRuleContext decl) {
                Origin cur = env.get(decl);
                if (cur != null && (cur.unique || cur.localFresh)
                        && !cur.external && !cur.aliasesThis && cur.params.isEmpty()) {
                    env.put(decl, cur.absorbElement(taint));
                }
            }
        }

        private boolean isUnattachedUniqueOrImmutable(Origin orig, Type type) {
            return orig == Origin.IMMUTABLE || isDeeplyImmutable(type) || (orig.unique && orig.uniqueOwner == null);
        }

        private ParserRuleContext resolveOwnedLocalDecl(BabbleParser.ExpressionContext expr) {
            BabbleParser.ExpressionContext unwrapped = unwrapParens(expr);
            if (!(unwrapped instanceof BabbleParser.SelectorContext sel) || sel.expression() != null) {
                return null;
            }
            ScopeNode occScope = checker.occurrenceScopeOf(sel);
            Object resolved = checker.resolveNameForEffects(sel.ID().getText(), occScope);
            if (!(resolved instanceof ParserRuleContext decl)) {
                return null;
            }
            if (paramIndex.containsKey(decl)) {
                return decl;
            }
            ScopeNode declScope = pass.definitionScopes.get(decl);
            ScopeAnalysis.DefKind dKind = decl instanceof BabbleParser.DefExpressionContext d
                    ? pass.analysis.defTarget(d).kind() : null;
            if (owner instanceof BabbleParser.FunctionLiteralContext && isOwnedScopeLocal(decl, declScope, dKind)) {
                return decl;
            }
            return null;
        }

        private Origin bindLocalOrigin(ParserRuleContext decl, Origin rhs, Type localType) {
            if (isDeeplyImmutable(localType)) {
                return Origin.IMMUTABLE;
            }
            if (rhs.unique && rhs.uniqueOwner == null) {
                return Origin.ofUnique(decl, decl, false);
            }
            if (rhs.localFresh && rhs.uniqueOwner == null) {
                return new Origin((BitSet) rhs.params.clone(), rhs.aliasesThis, rhs.external,
                        false, true, false, decl,
                        (BitSet) rhs.elementParams.clone(), rhs.elementThis, rhs.elementExternal,
                        Set.of(decl), new PathKey(decl, List.of()));
            }
            if ((rhs.unique || rhs.localFresh) && rhs.uniqueOwner != null) {
                // Aliasing a live unique/localFresh local without move(...) drops uniqueness while preserving provenance.
                demoteUniqueOwner(rhs.uniqueOwner, Origin.IMMUTABLE);
                return withLocalAliasKey(rhs.demoteAlias(Origin.IMMUTABLE), decl, localType);
            }
            return withLocalAliasKey(rhs, decl, localType);
        }

        private static boolean containsFrozen(Type type) {
            if (type == null) {
                return false;
            }
            if (type instanceof Type.Frozen) {
                return true;
            }
            if (type instanceof Type.Union u) {
                for (Type m : u.members()) {
                    if (m instanceof Type.Frozen) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void reportOnce(String msg, ParserRuleContext at) {
            if (!emitDiagnostics) {
                return;
            }
            String key = at.getStart().getLine() + ":" + at.getStart().getCharPositionInLine() + ":" + msg;
            if (reportedMessages.add(key)) {
                checker.effectDiagnostic(msg, at);
            }
        }

        private boolean isConstrainedPureOrHermetic() {
            return !declaredPrefix.axiom()
                    && (declaredPrefix.effects().contains(Effect.PURE) || declaredPrefix.effects().contains(Effect.HERMETIC));
        }

        Origin evalBlock(BabbleParser.BlockContext block, boolean isFunctionBody) {
            return evalBlock(block, isFunctionBody, false);
        }

        Origin evalBlock(BabbleParser.BlockContext block, boolean isFunctionBody, boolean discardLast) {
            if (block == null) {
                return Origin.IMMUTABLE;
            }
            List<BabbleParser.ExpressionContext> stmts = block.expression();
            Origin last = Origin.IMMUTABLE;
            for (int i = 0; i < stmts.size(); i++) {
                BabbleParser.ExpressionContext stmt = stmts.get(i);
                if (i < stmts.size() - 1 || discardLast) {
                    evalDiscarded(stmt);
                    last = Origin.IMMUTABLE;
                } else {
                    last = eval(stmt);
                }
            }
            return last;
        }

        void evalDiscarded(BabbleParser.ExpressionContext stmt) {
            if (stmt == null) {
                return;
            }
            if (stmt instanceof BabbleParser.DefExpressionContext
                    || stmt instanceof BabbleParser.AxiomExpressionContext
                    || stmt instanceof BabbleParser.TypeAliasExpressionContext
                    || stmt instanceof BabbleParser.AssignExpressionContext
                    || stmt instanceof BabbleParser.ReturnExpressionContext
                    || stmt instanceof BabbleParser.WhileExpressionContext
                    || stmt instanceof BabbleParser.ForExpressionContext
                    || stmt instanceof BabbleParser.BreakExpressionContext
                    || stmt instanceof BabbleParser.ContinueExpressionContext) {
                eval(stmt);
                return;
            }
            if (stmt instanceof BabbleParser.BlockExpressionContext be) {
                evalBlock(be.block(), false, true);
                return;
            }
            boolean prevHermetic = hermetic;
            boolean prevNothrow = nothrow;
            boolean prevSideEffect = stmtHasSideEffect;
            boolean prevError = stmtHasError;
            hermetic = true;
            nothrow = true;
            stmtHasSideEffect = false;
            stmtHasError = false;
            ParserRuleContext prevDiscarded = discardedExpr;
            discardedExpr = unwrapParens(stmt);
            try {
                eval(stmt);
            } finally {
                discardedExpr = prevDiscarded;
            }
            boolean stmtPure = hermetic && !stmtHasSideEffect;
            boolean stmtNothrow = nothrow;
            boolean hadError = stmtHasError;
            hermetic = prevHermetic && hermetic;
            nothrow = prevNothrow && nothrow;
            stmtHasSideEffect = prevSideEffect || stmtHasSideEffect || !stmtPure;
            stmtHasError = prevError || hadError;
            if (stmtPure && stmtNothrow && !hadError && !hasPriorDiagnostic(stmt)) {
                Type stmtType = checker.rawTypeOf(stmt);
                if (stmtType != Type.ERROR) {
                    reportOnce("Unused pure expression", stmt);
                }
            }
        }

        private boolean hasPriorDiagnostic(ParserRuleContext stmt) {
            if (stmt == null || stmt.getStart() == null || stmt.getStop() == null) {
                return false;
            }
            if (checker.hasTypeDiagnosticIn(stmt)) {
                return true;
            }
            int startLine = stmt.getStart().getLine();
            int startCol = stmt.getStart().getCharPositionInLine() + 1;
            int endLine = stmt.getStop().getLine();
            int endCol = stmt.getStop().getCharPositionInLine() + Math.max(1, stmt.getStop().getText().length());
            for (ScopeAnalysis.Warning w : pass.analysis.typeDiagnostics()) {
                if ((w.line() > startLine || (w.line() == startLine && w.column() >= startCol))
                        && (w.line() < endLine || (w.line() == endLine && w.column() <= endCol))) {
                    return true;
                }
            }
            return false;
        }

        void evalClassBody(BabbleParser.BlockContext block) {
            if (block == null) {
                return;
            }
            for (BabbleParser.ExpressionContext stmt : block.expression()) {
                if (stmt instanceof BabbleParser.DefExpressionContext def && def.value instanceof BabbleParser.FunctionLiteralContext) {
                    // Method declarations don't execute during construction.
                    continue;
                }
                evalDiscarded(stmt);
            }
        }

        Origin eval(ParserRuleContext ctx) {
            if (ctx == null) {
                return Origin.IMMUTABLE;
            }
            if (ctx instanceof BabbleParser.ExpressionContext expr && checker.rawTypeOf(expr) == Type.ERROR) {
                stmtHasError = true;
            }
            if (ctx instanceof BabbleParser.BlockContext b) {
                return evalBlock(b, false);
            }
            if (ctx instanceof BabbleParser.BlockExpressionContext be) {
                return evalBlock(be.block(), false);
            }
            if (ctx instanceof BabbleParser.ForeverExpressionContext f) {
                return eval(f.expression());
            }
            if (ctx instanceof BabbleParser.NullContext
                    || ctx instanceof BabbleParser.BooleanContext
                    || ctx instanceof BabbleParser.IntegerContext
                    || ctx instanceof BabbleParser.DoubleContext
                    || ctx instanceof BabbleParser.StringContext) {
                return Origin.IMMUTABLE;
            }
            if (ctx instanceof BabbleParser.ThisExpressionContext) {
                ScopeNode ts = BlockScopes.thisScope(checker.occurrenceScopeOf(ctx));
                Type thisType = ts != null && ts.type != null ? new Type.Instance(ts.type) : Type.ANY;
                return Origin.ofThis(THIS_KEY, isDeeplyImmutable(thisType));
            }
            if (ctx instanceof BabbleParser.RecurseContext) {
                terminating = false;
                return Origin.IMMUTABLE;
            }
            if (ctx instanceof BabbleParser.FunctionLiteralContext lit) {
                return checkClosureCaptureEscape(lit);
            }
            if (ctx instanceof BabbleParser.ClassExpressionContext || ctx instanceof BabbleParser.InterfaceExpressionContext) {
                return Origin.IMMUTABLE;
            }
            if (ctx instanceof BabbleParser.ObjectExpressionContext obj) {
                // Inline `object (...)` executes its createBlock right here under enclosing constraints!
                boolean prevConstructing = constructingSelf;
                constructingSelf = true;
                boolean objUnique = true;
                try {
                    for (BabbleParser.ExpressionContext s : obj.createBlock.expression()) {
                        if (s instanceof BabbleParser.DefExpressionContext def) {
                            if (def.value instanceof BabbleParser.FunctionLiteralContext) {
                                continue;
                            }
                            Origin init = def.value != null ? eval(def.value) : Origin.IMMUTABLE;
                            Type defType = checker.typeOfDeclarationForEffects(def);
                            if (!isUnattachedUniqueOrImmutable(init, defType)) {
                                objUnique = false;
                                if (init.unique && init.uniqueOwner != null) {
                                    demoteUniqueOwner(init.uniqueOwner, Origin.IMMUTABLE);
                                }
                            }
                            markStoredOrigin(init, "field " + def.name.getText(), def);
                        } else {
                            evalDiscarded(s);
                        }
                    }
                } finally {
                    constructingSelf = prevConstructing;
                }
                boolean imm = isDeeplyImmutable(checker.rawTypeOf(obj));
                return objUnique ? Origin.ofUnique(obj, null, imm) : Origin.ofExternal(obj, imm);
            }
            if (ctx instanceof BabbleParser.DefExpressionContext def) {
                return evalDef(def);
            }
            if (ctx instanceof BabbleParser.AssignExpressionContext assign) {
                return evalAssign(assign);
            }
            if (ctx instanceof BabbleParser.SelectorContext sel) {
                return evalSelector(sel);
            }
            if (ctx instanceof BabbleParser.CallContext call) {
                return evalCall(call);
            }
            if (ctx instanceof BabbleParser.IfExpressionContext ifCtx) {
                return evalIf(ifCtx.ifExpr());
            }
            if (ctx instanceof BabbleParser.IfExprContext ifCtx) {
                return evalIf(ifCtx);
            }
            if (ctx instanceof BabbleParser.WhileExpressionContext whileCtx) {
                return evalWhile(whileCtx);
            }
            if (ctx instanceof BabbleParser.ForExpressionContext forCtx) {
                return evalFor(forCtx);
            }
            if (ctx instanceof BabbleParser.BooleanOpContext boolOp) {
                return evalBooleanOp(boolOp);
            }
            if (ctx instanceof BabbleParser.BooleanNotContext notCtx) {
                eval(notCtx.expression());
                return Origin.IMMUTABLE;
            }
            if (ctx instanceof BabbleParser.NegateContext neg) {
                eval(neg.operand);
                return Origin.IMMUTABLE;
            }
            if (ctx instanceof BabbleParser.BinaryOpContext bin) {
                return evalBinaryOp(bin);
            }
            if (ctx instanceof BabbleParser.IsExpressionContext isCtx) {
                eval(isCtx.left);
                return Origin.IMMUTABLE;
            }
            if (ctx instanceof BabbleParser.NonNullAssertionContext nna) {
                Origin orig = eval(nna.expression());
                if (pass.analysis.nonNullAssertionTarget(nna) != null) {
                    nothrow = false;
                }
                Type dst = checker.rawTypeOf(nna);
                return isDeeplyImmutable(dst) ? Origin.IMMUTABLE : orig;
            }
            if (ctx instanceof BabbleParser.OverloadExpressionContext ov) {
                for (BabbleParser.ExpressionContext op : ov.expression()) {
                    eval(op);
                }
                return Origin.IMMUTABLE;
            }
            if (ctx instanceof BabbleParser.TypeApplyContext ta) {
                return eval(ta.expression());
            }
            if (ctx instanceof BabbleParser.CoreCollectionContext) {
                return Origin.IMMUTABLE;
            }
            if (ctx instanceof BabbleParser.AsExpressionContext asCtx) {
                Origin leftOrig = eval(asCtx.left);
                Type src = effectiveType(asCtx.left);
                Type dst = checker.rawTypeOf(asCtx);
                if (!isRedundantCast(src, dst)) {
                    nothrow = false;
                }
                return isDeeplyImmutable(dst) ? Origin.IMMUTABLE : leftOrig;
            }
            if (ctx instanceof BabbleParser.ReturnExpressionContext ret) {
                stmtHasSideEffect = true;
                Origin val = ret.expression() != null ? eval(ret.expression()) : Origin.IMMUTABLE;
                recordReturnOrigin(val, ret);
                return Origin.IMMUTABLE;
            }
            if (ctx instanceof BabbleParser.BreakExpressionContext brk) {
                stmtHasSideEffect = true;
                Origin val = brk.expression() != null ? eval(brk.expression()) : Origin.IMMUTABLE;
                Origin[] acc = loopBreakOrigins.peek();
                if (acc != null) {
                    acc[0] = acc[0] == null ? val : acc[0].join(val);
                }
                return Origin.IMMUTABLE;
            }
            if (ctx instanceof BabbleParser.ContinueExpressionContext) {
                stmtHasSideEffect = true;
                return Origin.IMMUTABLE;
            }
            if (ctx instanceof BabbleParser.SuperExpressionContext) {
                return Origin.ofThis(THIS_KEY, false);
            }
            return Origin.IMMUTABLE;
        }

        private Origin evalDef(BabbleParser.DefExpressionContext def) {
            stmtHasSideEffect = true;
            ScopeAnalysis.DefKind kind = pass.analysis.defTarget(def).kind();
            if (kind == ScopeAnalysis.DefKind.REQUIRED) {
                return Origin.IMMUTABLE;
            }
            Origin init = def.value != null ? eval(def.value) : Origin.IMMUTABLE;
            ScopeNode defScope = pass.definitionScopes.get(def);
            Type defType = checker.typeOfDeclarationForEffects(def);
            if (kind == ScopeAnalysis.DefKind.MEMBER) {
                if (!constructingSelf) {
                    hermetic = false;
                }
                if (init.unique && init.uniqueOwner != null) {
                    demoteUniqueOwner(init.uniqueOwner, Origin.IMMUTABLE);
                }
                markStoredOrigin(init, "field " + def.name.getText(), def);
            } else if (isOwnedScopeLocal(def, defScope, kind)) {
                // Local variable in this function (or top-level binding when verifying top-level file/static block)!
                Origin localOrig = owner instanceof BabbleParser.FunctionLiteralContext
                        ? bindLocalOrigin(def, init, defType)
                        : (isDeeplyImmutable(defType) ? Origin.IMMUTABLE : withLocalAliasKey(init, def, defType));
                env.put(def, localOrig);
            } else {
                if (init.unique && init.uniqueOwner != null) {
                    demoteUniqueOwner(init.uniqueOwner, Origin.IMMUTABLE);
                }
                markStoredOrigin(init, "outer binding " + def.name.getText(), def);
            }
            return Origin.IMMUTABLE;
        }

        private boolean isOwnedScopeLocal(ParserRuleContext decl, ScopeNode declScope, ScopeAnalysis.DefKind kind) {
            if (kind == ScopeAnalysis.DefKind.STATIC || declScope == null || escapedCapturedLocals.contains(decl)) {
                return false;
            }
            BabbleParser.FunctionLiteralContext declFn = enclosingFunctionOfDecl(decl);
            if (owner instanceof BabbleParser.FunctionLiteralContext) {
                return declFn == owner
                        && declScope.kind != ScopeNode.Kind.FILE;
            }
            if (owner instanceof BabbleParser.FileContext || owner instanceof BabbleParser.BlockContext) {
                return declFn == null;
            }
            return false;
        }

        private Origin withLocalAliasKey(Origin base, Object localKey, Type type) {
            if (isDeeplyImmutable(type)) {
                return Origin.IMMUTABLE;
            }
            Set<Object> keys = new HashSet<>(base.directKeys);
            keys.add(localKey);
            return new Origin((BitSet) base.params.clone(), base.aliasesThis, base.external,
                    Set.copyOf(keys), base.pathKey != null ? base.pathKey : new PathKey(localKey, List.of()));
        }

        private Origin evalAssign(BabbleParser.AssignExpressionContext assign) {
            stmtHasSideEffect = true;
            String compoundOp = Operators.compound(assign.op.getText());
            BabbleParser.ExpressionContext recv = ScopeAnalysis.assignReceiver(assign);
            String assignedName = ScopeAnalysis.assignName(assign);
            if (recv == null) {
                Origin rhs = eval(assign.value);
                if (compoundOp != null) {
                    checkCompoundArithmeticEffects(compoundOp, checker.rawTypeOf(assign), assign);
                }
                ScopeNode occScope = checker.occurrenceScopeOf(assign);
                Object resolved = checker.resolveNameForEffects(assignedName, occScope);
                if (resolved instanceof ParserRuleContext decl) {
                    if (compoundOp != null) {
                        Origin cur = env.get(decl);
                        if (cur != null && cur.moved) {
                            reportOnce("Use of moved variable " + assignedName, assign);
                        }
                    }
                    if (paramIndex.containsKey(decl)) {
                        // Local parameter variable reassignment (rebinds local slot, does NOT mutate caller's object!).
                        Type pType = checker.typeOfDeclarationForEffects(decl);
                        Origin merged = compoundOp != null ? Origin.IMMUTABLE : bindLocalOrigin(decl, rhs, pType);
                        env.put(decl, merged);
                        return merged;
                    }
                    BabbleParser.FunctionLiteralContext declFn = enclosingFunctionOfDecl(decl);
                    ScopeNode declScope = pass.definitionScopes.get(decl);
                    ScopeAnalysis.DefKind dKind = decl instanceof BabbleParser.DefExpressionContext d
                            ? pass.analysis.defTarget(d).kind() : null;
                    if (isOwnedScopeLocal(decl, declScope, dKind)) {
                        Type dType = checker.typeOfDeclarationForEffects(decl);
                        Origin next = compoundOp != null ? Origin.IMMUTABLE
                                : (owner instanceof BabbleParser.FunctionLiteralContext
                                        ? bindLocalOrigin(decl, rhs, dType)
                                        : withLocalAliasKey(rhs, decl, dType));
                        env.put(decl, next);
                        return next;
                    }
                    if (declFn != null && frameChain.contains(declFn) && !escapedCapturedLocals.contains(decl)) {
                        // Frame-local (`borrow`) helper closure reassigning enclosing F's unescaped local `def` (§4.5)!
                        frameWriteOwners.add(declFn);
                        if (rhs.unique && rhs.uniqueOwner != null) {
                            demoteUniqueOwner(rhs.uniqueOwner, Origin.IMMUTABLE);
                        }
                        markStoredOrigin(rhs, "outer variable " + assignedName, assign);
                        return rhs;
                    }
                    // Outer / package / file / static variable write -> breaks hermetic/pure!
                    hermetic = false;
                    if (rhs.unique && rhs.uniqueOwner != null) {
                        demoteUniqueOwner(rhs.uniqueOwner, Origin.IMMUTABLE);
                    }
                    markStoredOrigin(rhs, "outer variable " + assignedName, assign);
                    return rhs;
                }
                hermetic = false;
                return rhs;
            }

            // Member assignment: `receiver.field = value` (or compound `+=`, etc.)
            Origin recvOrig = eval(recv);
            Origin rhs = eval(assign.value);
            if (compoundOp != null) {
                checkCompoundArithmeticEffects(compoundOp, checker.rawTypeOf(assign), assign);
            }
            Type recvType = effectiveType(recv);
            if (recvType == Type.ERROR || recvType == Type.ANY || Type.containsNull(recvType)
                    || isPrimitiveOrString(recvType) || recvType instanceof Type.Function) {
                return rhs;
            }
            if (recvType instanceof Type.JavaClassValue || recvType instanceof Type.ClassValue
                    || recvType instanceof Type.Module) {
                // Static / module field write -> global mutation!
                hermetic = false;
                if (rhs.unique && rhs.uniqueOwner != null) {
                    demoteUniqueOwner(rhs.uniqueOwner, Origin.IMMUTABLE);
                }
                markStoredOrigin(rhs, "static member " + assignedName, assign);
                return rhs;
            }
            recordTargetMutation(recvOrig, recv, "field assignment ." + assignedName, assign, true);
            markStoredIntoTarget(recvOrig, rhs, effectiveType(assign.value), assignedName, assign);
            return rhs;
        }

        private void checkCompoundArithmeticEffects(String op, Type resType, BabbleParser.AssignExpressionContext assign) {
            if ("+".equals(op) && resType == Type.STRING) {
                applyEffects(toStringProfile(effectiveType(assign.value)));
            }
        }

        private Origin evalSelector(BabbleParser.SelectorContext sel) {
            if (sel.expression() == null) {
                ScopeNode occScope = checker.occurrenceScopeOf(sel);
                Object resolved = checker.resolveNameForEffects(sel.ID().getText(), occScope);
                if (resolved instanceof ParserRuleContext decl) {
                    Type declType = checker.rawTypeOf(sel);
                    boolean imm = isDeeplyImmutable(declType);
                    boolean narrowedImm = declType != null && declType != Type.ERROR && declType != Type.NOTHING && imm;
                    if (paramIndex.containsKey(decl)) {
                        Origin orig = env.getOrDefault(decl, Origin.ofParam(paramIndex.get(decl), decl, imm));
                        if (orig.moved) {
                            reportOnce("Use of moved variable " + sel.ID().getText(), sel);
                        }
                        return (!orig.moved && narrowedImm) ? Origin.IMMUTABLE : orig;
                    }
                    if (pseudoForwarded.containsKey(decl)) {
                        return Origin.ofParam(pseudoForwarded.get(decl), decl, false);
                    }
                    BabbleParser.FunctionLiteralContext declFn = enclosingFunctionOfDecl(decl);
                    ScopeNode declScope = pass.definitionScopes.get(decl);
                    ScopeAnalysis.DefKind dKind = decl instanceof BabbleParser.DefExpressionContext d
                            ? pass.analysis.defTarget(d).kind() : null;
                    if (isOwnedScopeLocal(decl, declScope, dKind)) {
                        if (escapedCapturedLocals.contains(decl) && checker.isReassigned(decl)) {
                            deterministic = false;
                        }
                        Origin orig = env.getOrDefault(decl, Origin.ofExternal(decl, imm));
                        if (orig.moved) {
                            reportOnce("Use of moved variable " + sel.ID().getText(), sel);
                        }
                        return (!orig.moved && narrowedImm) ? Origin.IMMUTABLE : orig;
                    }
                    if (declFn != null && frameChain.contains(declFn) && !escapedCapturedLocals.contains(decl)) {
                        // Frame-local helper closure reading F's unescaped local `def`!
                        frameReadOwners.add(declFn);
                        return Origin.ofExternal(decl, imm);
                    }
                    // Outer / top-level / package binding read:
                    if (checker.isReassigned(decl) || !isImmutableOuterBinding(decl, declType)) {
                        deterministic = false;
                    }
                    return Origin.ofExternal(decl, imm);
                }
                if (resolved == null) {
                    Type selType = checker.rawTypeOf(sel);
                    if (selType == Type.ANY || selType == Type.NULLABLE_ANY || selType == Type.ERROR) {
                        deterministic = false;
                        nothrow = false;
                    }
                }
                return Origin.IMMUTABLE;
            }

            // Member read `receiver.field` or `receiver?.field`:
            Origin recvOrig = eval(sel.expression());
            Type recvType = effectiveType(sel.expression());
            Type resType = checker.rawTypeOf(sel);
            boolean immRes = isDeeplyImmutable(resType);
            String fieldName = ScopeAnalysis.selectorName(sel);

            // Check Java static or instance field access:
            ScopeAnalysis.JavaFieldTarget jft = checker.javaFieldTargetOf(sel);
            if (jft != null) {
                Field jf = jft.field();
                if (Modifier.isStatic(jf.getModifiers())) {
                    if (!Modifier.isFinal(jf.getModifiers())) {
                        deterministic = false;
                    }
                } else if (!Modifier.isFinal(jf.getModifiers())) {
                    recordTargetRead(recvOrig, recvType);
                }
                if (checker.hasCallCheck(sel)) {
                    nothrow = false;
                }
                return immRes ? Origin.IMMUTABLE : recvOrig.deriveMember(fieldName, false);
            }
            if (recvType instanceof Type.JavaClassValue jcv) {
                Field f = findJavaField(jcv.type(), fieldName);
                if (f != null && Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers())) {
                    deterministic = false;
                }
                if (checker.hasCallCheck(sel)) {
                    nothrow = false;
                }
                return Origin.ofExternal(null, immRes);
            }
            if (recvType instanceof Type.ClassValue cv) {
                TypeNode.Member sm = cv.node().statics.get(fieldName);
                if (sm != null && sm.declaration != null && (checker.isReassigned(sm.declaration) || !isImmutableConstantValue(resType))) {
                    deterministic = false;
                }
                return Origin.ofExternal(null, immRes);
            }
            if (recvType instanceof Type.Module mod) {
                if (!checker.isModuleExportImmutable(mod.module(), fieldName, resType)) {
                    deterministic = false;
                }
                return Origin.ofExternal(null, immRes);
            }
            if (checker.hasCallCheck(sel)) {
                nothrow = false;
            }
            if (resType instanceof Type.Function) {
                boundMethodReceivers.put(sel, recvOrig);
            } else if (readsMutableBabbleMember(recvType)) {
                recordTargetRead(recvOrig, recvType);
            }
            return recvOrig.deriveMember(fieldName, immRes);
        }

        private boolean readsMutableBabbleMember(Type recvType) {
            if (recvType == null || containsFrozen(recvType) || isDeeplyImmutable(recvType)) {
                return false;
            }
            for (Type m : Type.membersOf(Type.withoutNull(recvType))) {
                if (m instanceof Type.Instance inst && inst.node().kind != ClassShape.Kind.VALUE_CLASS) {
                    return true;
                }
            }
            return false;
        }

        private boolean isImmutableOuterBinding(ParserRuleContext decl, Type type) {
            if (checker.isReassigned(decl)) {
                return false;
            }
            return isImmutableConstantValue(type);
        }

        private Field findJavaField(Class<?> cls, String name) {
            try {
                return cls.getField(name);
            } catch (NoSuchFieldException e) {
                return null;
            }
        }

        private Origin evalCall(BabbleParser.CallContext call) {
            return evalCallLike(call, call.expression(), call.callParameters().callParameter());
        }

        private Origin evalCallLike(ParserRuleContext call,
                                    BabbleParser.ExpressionContext calleeExpr,
                                    List<BabbleParser.CallParameterContext> args) {
            if (effectiveType(calleeExpr) instanceof Type.CoreCollection cc) {
                return evalCoreCollectionCall(cc.kind(), call, args);
            }
            if (effectiveType(calleeExpr) instanceof Type.ArrayOfFactory) {
                return evalArrayOfCall(call, args);
            }
            BabbleParser.ExpressionContext unwrappedCallee = calleeExpr instanceof BabbleParser.TypeApplyContext ta
                    ? ta.expression() : calleeExpr;
            // Evaluate receiver / callee expression and arguments!
            Origin recvOrig = Origin.IMMUTABLE;
            BabbleParser.ExpressionContext receiverExpr = null;
            Type arrayRecvType = null;
            Type.Array arrayRecv = null;
            String arrayMethodName = null;
            if (unwrappedCallee instanceof BabbleParser.SelectorContext sel && sel.expression() != null) {
                receiverExpr = sel.expression();
                Type recvType = effectiveType(receiverExpr);
                Type unwrappedRecv = TypeAlgebra.unwrapFrozen(recvType);
                if (unwrappedRecv instanceof Type.Array arr) {
                    String mName = ScopeAnalysis.selectorName(sel);
                    if (isArrayProtocolMethod(mName)) {
                        arrayRecvType = recvType;
                        arrayRecv = arr;
                        arrayMethodName = mName;
                    }
                }
                if (!(recvType instanceof Type.JavaPackage || recvType instanceof Type.Module)) {
                    boolean preserveSizeDet = "size".equals(arrayMethodName) && isNonReassignedBareBinding(receiverExpr);
                    boolean detBefore = deterministic;
                    recvOrig = eval(receiverExpr);
                    if (preserveSizeDet) {
                        deterministic = detBefore;
                    }
                }
            } else {
                eval(calleeExpr);
            }

            Origin[] argOrigins = new Origin[args.size()];
            BabbleParser.ExpressionContext[] argExprs = new BabbleParser.ExpressionContext[args.size()];
            for (int i = 0; i < args.size(); i++) {
                argExprs[i] = args.get(i).expression();
                argOrigins[i] = eval(argExprs[i]);
                Type argType = effectiveType(argExprs[i]);
                for (Type m : Type.membersOf(argType)) {
                    if (m instanceof Type.Function fn) {
                        BabbleParser.FunctionLiteralContext lit = checker.literalOf(fn);
                        CallableSummary innerSum = lit != null ? summaries.get(lit) : null;
                        if (innerSum != null && !innerSum.frameWriteOwners().isEmpty()) {
                            stmtHasSideEffect = true;
                        }
                    }
                }
            }

            Type resType = checker.rawTypeOf(call);
            if (arrayMethodName != null) {
                return evalArrayMethodCall(call, receiverExpr, recvOrig, arrayRecvType, arrayRecv,
                        arrayMethodName, args, argExprs, argOrigins, resType);
            }
            Type calleeType = resolveCalleeType(calleeExpr);

            // 1. Built-in `import` / scalar conversions
            if (calleeExpr instanceof BabbleParser.CoreScalarRefContext) {
                return evalCoreConversionCall(resType, argExprs);
            }
            if (isImportCall(calleeExpr)) {
                hermetic = false;
                deterministic = false;
                nothrow = false;
                terminating = false;
                return Origin.ofExternal(call, isDeeplyImmutable(resType));
            }
            if (calleeType == OperatorTypes.DISCARD_FUNCTION) {
                stmtHasSideEffect = true;
                return Origin.IMMUTABLE;
            }
            if (calleeType == OperatorTypes.MOVE_FUNCTION) {
                if (argExprs.length != 1) {
                    return Origin.IMMUTABLE;
                }
                ParserRuleContext decl = resolveOwnedLocalDecl(argExprs[0]);
                Origin orig = argOrigins[0];
                if (decl == null) {
                    reportOnce("Cannot move expression : target must be a unique local variable", argExprs[0]);
                    return Origin.ofExternal(call, isDeeplyImmutable(resType));
                }
                if (orig.moved) {
                    return Origin.ofUnique(call, null, isDeeplyImmutable(resType));
                }
                if (paramIndex.containsKey(decl) && borrowParams[paramIndex.get(decl)]) {
                    reportOnce("Cannot move borrowed parameter " + argExprs[0].getText(), argExprs[0]);
                    return Origin.ofExternal(call, isDeeplyImmutable(resType));
                }
                if (!orig.unique) {
                    reportOnce("Cannot move " + argExprs[0].getText() + " : variable is not unique", argExprs[0]);
                    return Origin.ofExternal(call, isDeeplyImmutable(resType));
                }
                env.put(decl, Origin.MOVED);
                return Origin.ofUnique(call, null, isDeeplyImmutable(resType));
            }
            if (calleeType == OperatorTypes.FREEZE_FUNCTION) {
                if (argExprs.length != 1) {
                    return Origin.IMMUTABLE;
                }
                Origin orig = argOrigins[0];
                Type argType = effectiveType(argExprs[0]);
                if (!orig.moved) {
                    if (orig.unique) {
                        if (orig.uniqueOwner instanceof ParserRuleContext decl) {
                            if (paramIndex.containsKey(decl) && borrowParams[paramIndex.get(decl)]) {
                                reportOnce("Cannot freeze borrowed parameter " + argExprs[0].getText(), argExprs[0]);
                            } else {
                                env.put(decl, Origin.MOVED);
                            }
                        }
                    } else if (!isDeeplyImmutable(argType) && orig != Origin.IMMUTABLE && argType != Type.ERROR) {
                        reportOnce("Cannot freeze expression : value is not unique or deeply immutable", argExprs[0]);
                    }
                }
                return Origin.IMMUTABLE;
            }

            // 2. Java method call (fast-path or dynamic reflective overload set)
            Method rm = checker.resolvedJavaMethodOf(call);
            if (rm != null) {
                JavaCallProfile profile = resolveJavaMethodProfile(rm.getDeclaringClass(), rm.getName(), rm);
                BabbleParser.ExpressionContext instanceRecv = Modifier.isStatic(rm.getModifiers()) ? null : receiverExpr;
                return applyJavaCall(call, calleeExpr, profile, rm.getDeclaringClass(), rm.getName(),
                        rm.getParameterTypes(), rm.getGenericParameterTypes(), checker.isResolvedJavaVarArgs(call),
                        instanceRecv, recvOrig, argExprs, argOrigins, resType);
            }
            if (calleeType instanceof Type.JavaMethod jm) {
                JavaCallProfile profile = resolveJavaMethodProfile(jm.owner(), jm.name(), null);
                Class<?>[] samTypes = findOverloadSamTypes(jm.owner(), jm.name(), args.size());
                boolean isClassRecv = receiverExpr != null && checker.rawTypeOf(receiverExpr) instanceof Type.JavaClassValue;
                return applyJavaCall(call, calleeExpr, profile, jm.owner(), jm.name(),
                        samTypes, samTypes, false, isClassRecv ? null : receiverExpr, recvOrig, argExprs, argOrigins, resType);
            }

            // 3. Java constructor call or Java functional interface SAM conversion `Predicate(fn)`
            Constructor<?> rc = checker.resolvedJavaConstructorOf(call);
            if (rc != null) {
                JavaCallProfile profile = resolveJavaConstructorProfile(rc.getDeclaringClass(), rc);
                return applyJavaCall(call, calleeExpr, profile, rc.getDeclaringClass(), "<init>",
                        rc.getParameterTypes(), rc.getGenericParameterTypes(), checker.isResolvedJavaVarArgs(call),
                        null, Origin.IMMUTABLE, argExprs, argOrigins, resType);
            }
            if (calleeType instanceof Type.JavaClassValue jcv) {
                if (jcv.type().isInterface() && args.size() == 1 && JavaTypes.isFunctionalInterface(jcv.type())) {
                    // Explicit SAM wrapper `Function(fn)` is pure deterministic nothrow itself (constructing proxy).
                    return argOrigins[0];
                }
                JavaCallProfile profile = resolveJavaConstructorProfile(jcv.type(), null);
                return applyJavaCall(call, calleeExpr, profile, jcv.type(), "<init>",
                        null, null, false, null, Origin.IMMUTABLE, argExprs, argOrigins, resType);
            }

            // 4. Babble class constructor call `Point(1, 2)`
            if (calleeType instanceof Type.ClassValue cv && !cv.node().isInterface()) {
                CallableSummary cs = constructorSummary(cv.node());
                boolean imm = isDeeplyImmutable(resType);
                boolean ctorHermetic = cs != null && cs.effects().isHermetic();
                Origin ctorOrigin = ctorHermetic ? Origin.ofUnique(call, null, imm) : Origin.ofExternal(call, imm);
                if (cs != null) {
                    applyEffects(cs.effects());
                    List<Type.Parameter> cParams = checker.constructorParamsForEffects(cv.node());
                    for (int i = 0; i < args.size(); i++) {
                        int pIdx = checker.parameterIndexForArg(cParams, args, args.get(i));
                        boolean storedInSelf = pIdx >= 0 && cs.ctorStoredParams().get(pIdx);
                        boolean borrowed = pIdx >= 0 && pIdx < cs.borrowParams().length && cs.borrowParams()[pIdx];
                        boolean uniqueP = pIdx >= 0 && pIdx < cs.uniqueParams().length && cs.uniqueParams()[pIdx];
                        if ((storedInSelf || !borrowed) && !uniqueP && !isUnattachedUniqueOrImmutable(argOrigins[i], effectiveType(argExprs[i]))) {
                            if ((argOrigins[i].unique || argOrigins[i].localFresh) && argOrigins[i].uniqueOwner != null) {
                                demoteUniqueOwner(argOrigins[i].uniqueOwner, Origin.IMMUTABLE);
                            }
                            if (ctorHermetic && !imm) {
                                ctorOrigin = ctorOrigin.absorbElement(argOrigins[i]);
                            }
                        }
                    }
                    applyBabbleArgEffects(call, calleeExpr, cParams, cs.mutParams(), cs.borrowParams(), cs.uniqueParams(),
                            null, Origin.IMMUTABLE, false, false, true, ctorHermetic, args, argExprs, argOrigins);
                }
                return ctorOrigin;
            }

            // 5. Babble `recurse(...)`
            if (calleeExpr instanceof BabbleParser.RecurseContext && owner instanceof BabbleParser.FunctionLiteralContext selfFn) {
                CallableSummary selfSum = summaries.getOrDefault(selfFn, initialSummary(selfFn));
                applyCallableSummaryEffects(selfSum);
                Type selfRaw = checker.rawTypeOf(selfFn);
                List<Type.Parameter> fParams = selfRaw instanceof Type.Function tf ? tf.parameters() : List.of();
                boolean hasRecv = canReferenceThis(ownerScope);
                Origin selfRecv = hasRecv ? Origin.ofThis(THIS_KEY, false) : Origin.IMMUTABLE;
                boolean[] mutableCopiedSpread = new boolean[args.size()];
                Origin[] postSpreadOrigins = applyBabbleArgEffects(call, calleeExpr, fParams, selfSum.mutParams(), selfSum.borrowParams(), selfSum.uniqueParams(),
                        null, selfRecv, selfSum.effects().mutThis(), selfSum.deepMutThis(), selfSum.effects().borrowThis(),
                        selfSum.effects().isHermetic(), args, argExprs, argOrigins, null, mutableCopiedSpread);
                if (selfSum.effects().uniqueResult()) {
                    return Origin.ofUnique(call, null, isDeeplyImmutable(resType));
                }
                return deriveCallReturnOrigin(call, resType, selfSum, hasRecv, selfRecv, argOrigins, postSpreadOrigins,
                        mutableCopiedSpread, fParams, args);
            }

            // 6. Babble function / method / overload set / union of functions
            if (calleeType instanceof Type.Overloaded) {
                TypeChecker.ResolvedOverloadCall resolved = call instanceof BabbleParser.CallContext cc
                        ? checker.resolvedOverloadCalls().get(cc) : null;
                if (resolved != null) {
                    if (!resolved.isSplit() && resolved.altType() != null) {
                        return applyBabbleCallableType(call, calleeExpr, resolved.altType(),
                                receiverExpr, recvOrig, args, argExprs, argOrigins, resType);
                    }
                    if (resolved.isSplit()) {
                        Origin combinedOut = Origin.IMMUTABLE;
                        Map<ParserRuleContext, Origin> initialEnv = new IdentityHashMap<>(env);
                        Map<ParserRuleContext, Origin> mergedEnv = null;
                        for (TypeChecker.ResolvedSplitBranch b : resolved.splitBranches()) {
                            env = new IdentityHashMap<>(initialEnv);
                            Origin branchOut = applyBabbleCallableMember(call, calleeExpr, b.altType(), b.spreadAdapts(),
                                    receiverExpr, recvOrig, args, argExprs, argOrigins, resType);
                            combinedOut = combinedOut == Origin.IMMUTABLE ? branchOut : combinedOut.join(branchOut);
                            mergedEnv = mergedEnv == null ? new IdentityHashMap<>(env) : mergeEnvs(mergedEnv, env);
                        }
                        if (mergedEnv != null) {
                            env = mergedEnv;
                        }
                        return combinedOut;
                    }
                }
            }
            if (calleeType instanceof Type.Function || calleeType instanceof Type.Union) {
                return applyBabbleCallableType(call, calleeExpr, calleeType, receiverExpr, recvOrig, args, argExprs, argOrigins, resType);
            }

            // Fallback (unresolved error type)
            return Origin.IMMUTABLE;
        }

        private Origin evalCoreConversionCall(Type target, BabbleParser.ExpressionContext[] argExprs) {
            if (argExprs.length != 1) {
                return Origin.IMMUTABLE;
            }
            Type src = effectiveType(argExprs[0]);
            if (target == Type.STRING) {
                applyEffects(toStringProfile(src));
                return Origin.IMMUTABLE;
            }
            boolean isTotalNumericWidening = Type.isNumericScalar(src) && Type.isNumericScalar(target)
                    && Type.numericRank(src) <= Type.numericRank(target);
            boolean isTotalCharWidening = src == Type.CHAR
                    && (target == Type.INT || target == Type.LONG || target == Type.FLOAT || target == Type.DOUBLE);
            if (target != src && !isTotalNumericWidening && !isTotalCharWidening) {
                nothrow = false;
            }
            return Origin.IMMUTABLE;
        }

        private Origin evalCoreCollectionCall(String kind, ParserRuleContext ccc,
                                              List<BabbleParser.CallParameterContext> paramsList) {
            BabbleParser.ExpressionContext[] argExprs = new BabbleParser.ExpressionContext[paramsList.size()];
            Origin[] argOrigins = new Origin[paramsList.size()];
            for (int i = 0; i < paramsList.size(); i++) {
                argExprs[i] = paramsList.get(i).expression();
                argOrigins[i] = eval(argExprs[i]);
            }
            if ("entry".equals(kind)) {
                boolean immutableEntry = true;
                boolean uniqueEntry = true;
                Origin freshEntry = Origin.ofUnique(ccc, null, false);
                for (int i = 0; i < argExprs.length; i++) {
                    Type argType = effectiveType(argExprs[i]);
                    if (!isDeeplyImmutable(argType)) {
                        immutableEntry = false;
                        if (!isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                            uniqueEntry = false;
                            freshEntry = freshEntry.absorbElement(argOrigins[i]);
                            if (argOrigins[i].unique && argOrigins[i].uniqueOwner != null) {
                                demoteUniqueOwner(argOrigins[i].uniqueOwner, Origin.IMMUTABLE);
                            }
                        }
                        markStoredOrigin(argOrigins[i], "entry", argExprs[i]);
                    }
                }
                if (!immutableEntry) {
                    applyEffects(EffectSet.ofInferred(
                            EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true));
                    return uniqueEntry ? Origin.ofUnique(ccc, null, false) : freshEntry;
                }
                return Origin.IMMUTABLE;
            }
            if ("array".equals(kind)) {
                applyEffects(EffectSet.ofInferred(EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC), false, true, false, true));
                nothrow = false;
                boolean staysUnique = true;
                Origin freshArr = Origin.ofUnique(ccc, null, false);
                for (int i = 0; i < paramsList.size(); i++) {
                    BabbleParser.CallParameterContext p = paramsList.get(i);
                    String name = p.memberName() != null ? p.memberName().getText() : null;
                    if ("from".equals(name)) {
                        Type rawFrom = effectiveType(argExprs[i]);
                        recordTargetRead(argOrigins[i], rawFrom);
                        Type fromType = rawFrom instanceof Type.Frozen f ? f.of() : rawFrom;
                        if (fromType instanceof Type.Array arr) {
                            staysUnique = isDeeplyImmutable(arr.element());
                        } else if (fromType instanceof Type.JavaInstance ji) {
                            JavaCallProfile iterProf = resolveJavaMethodProfile(ji.type(), "iterator", lookupPublicMethod(ji.type(), "iterator"));
                            if (!iterProf.effects().isHermetic()) {
                                terminating = false;
                            }
                            if (!ji.typeArguments().isEmpty()) {
                                for (Type ta : ji.typeArguments()) {
                                    if (!isDeeplyImmutable(ta)) {
                                        staysUnique = false;
                                    }
                                }
                            } else {
                                staysUnique = false;
                            }
                        } else {
                            staysUnique = false;
                            terminating = false;
                        }
                        if (!staysUnique) {
                            freshArr = freshArr.absorbElement(argOrigins[i]);
                        }
                    }
                }
                return staysUnique ? Origin.ofUnique(ccc, null, false) : freshArr;
            }

            // list, set, sortedset, map, sortedmap: hermetic deterministic allocation (unique per BIP007-D6!)
            applyEffects(EffectSet.ofInferred(EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true));
            boolean hasCapacity = false;
            boolean hasFrom = false;
            boolean hasComparator = false;
            boolean hasElements = false;
            boolean staysUnique = true;
            Origin freshCol = Origin.ofUnique(ccc, null, false);
            for (int i = 0; i < paramsList.size(); i++) {
                BabbleParser.CallParameterContext p = paramsList.get(i);
                String name = p.memberName() != null ? p.memberName().getText() : null;
                if ("capacity".equals(name)) {
                    hasCapacity = true;
                } else if ("from".equals(name)) {
                    hasFrom = true;
                    // `from:` reads and copies elements from the source collection; result carries elements' provenance (BIP019 §4.1, §4.3).
                    Type rawFrom = effectiveType(argExprs[i]);
                    recordTargetRead(argOrigins[i], rawFrom);
                    Type fromType = rawFrom;
                    if (fromType instanceof Type.Frozen f) {
                        fromType = f.of();
                    }
                    boolean fromImm = true;
                    if (fromType instanceof Type.JavaInstance ji && !ji.typeArguments().isEmpty()) {
                        for (Type ta : ji.typeArguments()) {
                            if (!isDeeplyImmutable(ta)) {
                                fromImm = false;
                            }
                        }
                    } else {
                        fromImm = false;
                    }
                    if (!fromImm) {
                        staysUnique = false;
                        freshCol = freshCol.absorbElement(argOrigins[i]);
                    }
                } else if ("comparator".equals(name)) {
                    hasComparator = true;
                    markStoredOrigin(argOrigins[i], kind + " comparator", argExprs[i]);
                    Type cmpType = effectiveType(argExprs[i]);
                    propagateSamCallbackEffects(cmpType, java.util.Comparator.class);
                    if (!(cmpType instanceof Type.Function cmpFn)
                            || !cmpFn.effects().isPure() || !cmpFn.effects().isDeterministic()) {
                        staysUnique = false;
                        freshCol = freshCol.absorbElement(argOrigins[i]);
                    }
                } else {
                    hasElements = true;
                    Type argType = effectiveType(argExprs[i]);
                    if (!isDeeplyImmutable(argType)) {
                        if (!isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                            staysUnique = false;
                            freshCol = freshCol.absorbElement(argOrigins[i]);
                            if (argOrigins[i].unique && argOrigins[i].uniqueOwner != null) {
                                demoteUniqueOwner(argOrigins[i].uniqueOwner, Origin.IMMUTABLE);
                            }
                        }
                        markStoredOrigin(argOrigins[i], kind, argExprs[i]);
                    }
                    if ("set".equals(kind)) {
                        applyEffects(equalityProfile(argType, argType));
                    } else if ("map".equals(kind)) {
                        if (argType instanceof Type.JavaInstance ji
                                && java.util.Map.Entry.class.isAssignableFrom(ji.type())
                                && !ji.typeArguments().isEmpty()) {
                            Type keyType = ji.typeArguments().get(0);
                            applyEffects(equalityProfile(keyType, keyType));
                        }
                    } else if ("sortedset".equals(kind) && !hasComparator) {
                        applyEffects(comparisonProfile(argType, argType));
                    } else if ("sortedmap".equals(kind) && !hasComparator) {
                        if (argType instanceof Type.JavaInstance ji
                                && java.util.Map.Entry.class.isAssignableFrom(ji.type())
                                && !ji.typeArguments().isEmpty()) {
                            Type keyType = ji.typeArguments().get(0);
                            applyEffects(comparisonProfile(keyType, keyType));
                        }
                    }
                }
            }
            if (hasCapacity || ((hasFrom || hasElements) && ("sortedset".equals(kind) || "sortedmap".equals(kind)))) {
                nothrow = false;
            }
            return staysUnique ? Origin.ofUnique(ccc, null, false) : freshCol;
        }

        private static boolean isArrayProtocolMethod(String name) {
            return "size".equals(name) || "get".equals(name) || "set".equals(name)
                    || "equals".equals(name) || "hashCode".equals(name) || "toString".equals(name);
        }

        private boolean isNonReassignedBareBinding(BabbleParser.ExpressionContext expr) {
            while (expr instanceof BabbleParser.BlockExpressionContext b && b.block().expression().size() == 1) {
                expr = b.block().expression(0);
            }
            if (expr instanceof BabbleParser.SelectorContext rsel && rsel.expression() == null) {
                Object resolved = checker.resolveNameForEffects(rsel.ID().getText(), checker.occurrenceScopeOf(rsel));
                return resolved instanceof ParserRuleContext decl && !checker.isReassigned(decl);
            }
            return false;
        }

        private Origin evalArrayOfCall(ParserRuleContext call, List<BabbleParser.CallParameterContext> paramsList) {
            BabbleParser.ExpressionContext[] argExprs = new BabbleParser.ExpressionContext[paramsList.size()];
            Origin[] argOrigins = new Origin[paramsList.size()];
            for (int i = 0; i < paramsList.size(); i++) {
                argExprs[i] = paramsList.get(i).expression();
                argOrigins[i] = eval(argExprs[i]);
            }
            applyEffects(EffectSet.ofInferred(EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true));
            boolean staysUnique = true;
            Origin freshArr = Origin.ofUnique(call, null, false);
            for (int i = 0; i < argExprs.length; i++) {
                Type argType = effectiveType(argExprs[i]);
                if (!isDeeplyImmutable(argType)) {
                    if (!isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                        staysUnique = false;
                        freshArr = freshArr.absorbElement(argOrigins[i]);
                        if (argOrigins[i].unique && argOrigins[i].uniqueOwner != null) {
                            demoteUniqueOwner(argOrigins[i].uniqueOwner, Origin.IMMUTABLE);
                        }
                    }
                    markStoredOrigin(argOrigins[i], "array", argExprs[i]);
                }
            }
            return staysUnique ? Origin.ofUnique(call, null, false) : freshArr;
        }

        private Origin evalArrayMethodCall(ParserRuleContext call,
                                           BabbleParser.ExpressionContext receiverExpr,
                                           Origin recvOrig,
                                           Type recvType,
                                           Type.Array arr,
                                           String methodName,
                                           List<BabbleParser.CallParameterContext> args,
                                           BabbleParser.ExpressionContext[] argExprs,
                                           Origin[] argOrigins,
                                           Type resType) {
            if (resType == Type.ERROR) {
                return Origin.IMMUTABLE;
            }
            return switch (methodName) {
                case "size" -> Origin.IMMUTABLE;
                case "get" -> {
                    nothrow = false;
                    recordTargetRead(recvOrig, recvType);
                    if (isDeeplyImmutable(resType)) {
                        yield Origin.IMMUTABLE;
                    }
                    Origin flat = recvOrig.hasElementTaint() ? recvOrig.flattenElements() : recvOrig;
                    yield new Origin(flat.params, flat.aliasesThis, flat.external, false, false, false, null,
                            Origin.EMPTY_BITS, false, false, Set.of(call), new PathKey(call, List.of()));
                }
                case "set" -> {
                    nothrow = false;
                    if (containsFrozen(recvType)) {
                        reportOnce("Cannot call mut method set on frozen value", call);
                    }
                    recordTargetMutation(recvOrig, receiverExpr, "mut method set", call, true);
                    int valIdx = -1;
                    for (int i = 0; i < args.size(); i++) {
                        if ("value".equals(ScopeAnalysis.callParamName(args.get(i)))) {
                            valIdx = i;
                            break;
                        }
                    }
                    if (valIdx < 0 && args.size() >= 2 && ScopeAnalysis.callParamName(args.get(1)) == null) {
                        valIdx = 1;
                    }
                    if (valIdx >= 0 && valIdx < argExprs.length) {
                        Origin valOrig = argOrigins[valIdx];
                        Type valType = effectiveType(argExprs[valIdx]);
                        if (!isDeeplyImmutable(valType)) {
                            if ((valOrig.unique || valOrig.localFresh) && valOrig.uniqueOwner != null) {
                                demoteUniqueOwner(valOrig.uniqueOwner, Origin.IMMUTABLE);
                            }
                            markStoredIntoTarget(recvOrig, valOrig, valType, "element", argExprs[valIdx]);
                        }
                    }
                    yield Origin.IMMUTABLE;
                }
                case "equals" -> {
                    applyEffects(singleEqualityProfile(arr.element()));
                    recordTargetRead(recvOrig, recvType);
                    if (argExprs.length == 1) {
                        recordTargetRead(argOrigins[0], effectiveType(argExprs[0]));
                    }
                    yield Origin.IMMUTABLE;
                }
                case "hashCode" -> {
                    applyEffects(singleEqualityProfile(arr.element()));
                    recordTargetRead(recvOrig, recvType);
                    yield Origin.IMMUTABLE;
                }
                case "toString" -> {
                    applyEffects(toStringProfile(arr.element()));
                    recordTargetRead(recvOrig, recvType);
                    yield Origin.IMMUTABLE;
                }
                default -> Origin.IMMUTABLE;
            };
        }

        private Type resolveCalleeType(BabbleParser.ExpressionContext calleeExpr) {
            if (specializedParams != null && calleeExpr instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
                Object r = checker.resolveNameForEffects(sel.ID().getText(), checker.occurrenceScopeOf(sel));
                if (r instanceof ParserRuleContext decl && specializedParams.containsKey(decl)) {
                    return specializedParams.get(decl);
                }
            }
            return checker.rawTypeOf(calleeExpr);
        }

        private boolean isImportCall(BabbleParser.ExpressionContext calleeExpr) {
            if (calleeExpr.getParent() instanceof BabbleParser.CallContext cc && BlockScopes.staticImport(cc) != null) {
                // Even static import("...ba") executes module initialization at runtime unless top-level!
                return true;
            }
            if (calleeExpr instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
                String name = sel.ID().getText();
                if (("import".equals(name) || "importe".equals(name))
                        && checker.resolveNameForEffects(name, checker.occurrenceScopeOf(sel)) == null) {
                    return true;
                }
            }
            return false;
        }

        private Class<?>[] findOverloadSamTypes(Class<?> cls, String name, int arity) {
            Class<?>[] res = new Class<?>[arity];
            for (Method m : cls.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity) {
                    java.lang.reflect.Type[] pts = m.getGenericParameterTypes();
                    for (int i = 0; i < arity; i++) {
                        Class<?> sam = samInterfaceForJavaType(pts[i]);
                        if (sam != null) {
                            res[i] = sam;
                        }
                    }
                }
            }
            return res;
        }

        private static Class<?> samInterfaceForJavaType(java.lang.reflect.Type genericType) {
            if (genericType instanceof Class<?> cls) {
                return JavaTypes.isFunctionalInterface(cls) ? cls : null;
            }
            if (genericType instanceof java.lang.reflect.ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
                return JavaTypes.isFunctionalInterface(raw) ? raw : null;
            }
            if (genericType instanceof java.lang.reflect.TypeVariable<?> tv) {
                Class<?> sam = null;
                Method samMethod = null;
                for (java.lang.reflect.Type bound : tv.getBounds()) {
                    if (bound == Object.class) {
                        continue;
                    }
                    Class<?> bClass = null;
                    if (bound instanceof Class<?> c) {
                        bClass = c;
                    } else if (bound instanceof java.lang.reflect.ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
                        bClass = raw;
                    }
                    if (bClass == null || !bClass.isInterface()) {
                        return null;
                    }
                    if (JavaTypes.abstractMethods(bClass).isEmpty()) {
                        continue;
                    }
                    Method m = JavaTypes.functionalMethod(bClass);
                    if (m == null) {
                        return null;
                    }
                    if (sam == null) {
                        sam = bClass;
                        samMethod = m;
                    } else if (!JavaTypeBridge.sameSamSignature(samMethod, m)) {
                        return null;
                    }
                }
                return sam;
            }
            return null;
        }

        private Origin applyJavaCall(ParserRuleContext call,
                                     BabbleParser.ExpressionContext calleeExpr,
                                     JavaCallProfile profile,
                                     Class<?> owner,
                                     String methodName,
                                     Class<?>[] javaParamTypes,
                                     java.lang.reflect.Type[] genericParamTypes,
                                     boolean varArgs,
                                     BabbleParser.ExpressionContext receiverExpr,
                                     Origin recvOrig,
                                     BabbleParser.ExpressionContext[] argExprs,
                                     Origin[] argOrigins,
                                     Type resType) {
            if (resType == Type.ERROR) {
                return Origin.IMMUTABLE;
            }
            applyEffects(profile.effects());
            if (checker.hasCallCheck(call) && !profile.explicitNothrow()) {
                nothrow = false;
            }

            // Receiver mut / borrow / frozen / read check:
            Type recvType = receiverExpr != null ? effectiveType(receiverExpr) : null;
            if (receiverExpr != null && profile.readsReceiver()) {
                recordTargetRead(recvOrig, recvType);
            }
            boolean effectiveMutRecv = profile.mutReceiver() && receiverExpr != null && recvType != Type.STRING;
            if (receiverExpr != null && containsFrozen(recvType)) {
                if (effectiveMutRecv) {
                    reportOnce("Cannot call mut method " + methodName + " on frozen value", call);
                } else if (!profile.borrowReceiver() && !profile.effects().isPure()) {
                    reportOnce("Cannot call non-borrow method " + methodName + " on frozen value", call);
                }
            }
            if (effectiveMutRecv) {
                recordTargetMutation(recvOrig, receiverExpr, "mut Java method " + calleeExpr.getText(), call, profile.shallow());
            }
            boolean recvCanEscape = !profile.borrowReceiver() && !profile.effects().isPure()
                    && !(profile.effects().isHermetic() && !anyTrue(profile.mutParams()) && isDeeplyImmutable(resType));
            if (receiverExpr != null && recvCanEscape) {
                if ((recvOrig.unique || recvOrig.localFresh) && recvOrig.uniqueOwner != null) {
                    demoteUniqueOwner(recvOrig.uniqueOwner, Origin.IMMUTABLE);
                }
                markStoredOrigin(recvOrig, "Java method call", call);
            }

            // Parameter mut / borrow / unique / frozen / read / SAM callback checks:
            boolean[] argIsMut = new boolean[argExprs.length];
            int fixed = varArgs && javaParamTypes != null ? javaParamTypes.length - 1 : -1;
            for (int i = 0; i < argExprs.length; i++) {
                Type argType = effectiveType(argExprs[i]);
                int pIdx = varArgs && fixed >= 0 ? Math.min(i, fixed) : i;
                boolean mutP = pIdx < profile.mutParams().length && profile.mutParams()[pIdx] && argType != Type.STRING;
                boolean borrowP = pIdx < profile.borrowParams().length && profile.borrowParams()[pIdx];
                boolean uniqueP = pIdx < profile.uniqueParams().length && profile.uniqueParams()[pIdx];
                boolean readP = pIdx < profile.readParams().length ? profile.readParams()[pIdx] : true;
                if (readP) {
                    recordTargetRead(argOrigins[i], argType);
                }
                argIsMut[i] = mutP;
                if (containsFrozen(argType)) {
                    if (mutP) {
                        reportOnce("Cannot pass frozen value to mut parameter", argExprs[i]);
                    } else if (uniqueP) {
                        reportOnce("Argument for unique parameter cannot be frozen", argExprs[i]);
                    } else if (!borrowP) {
                        reportOnce("Cannot pass frozen value to non-borrow Java parameter", argExprs[i]);
                    }
                }
                if (uniqueP && !argOrigins[i].moved) {
                    if (containsFrozen(argType)) {
                        // Already reported above.
                    } else if (!argOrigins[i].unique && argOrigins[i] != Origin.IMMUTABLE && !isDeeplyImmutable(argType) && argType != Type.ERROR) {
                        reportOnce("Argument for unique parameter must be unique", argExprs[i]);
                    } else if (!borrowP && argOrigins[i].unique && argOrigins[i].uniqueOwner instanceof ParserRuleContext decl) {
                        if (paramIndex.containsKey(decl) && borrowParams[paramIndex.get(decl)]) {
                            reportOnce("Cannot move borrowed parameter " + argExprs[i].getText(), argExprs[i]);
                        } else {
                            env.put(decl, Origin.MOVED);
                        }
                    }
                }
                if (mutP) {
                    recordTargetMutation(argOrigins[i], argExprs[i], "mut Java parameter", argExprs[i], false);
                }
                if (!borrowP) {
                    // Storing a non-unique, non-immutable arg into a unique/localFresh receiver keeps container localFresh while dropping unique (BIP016-D9)!
                    if ((recvOrig.unique || recvOrig.localFresh) && effectiveMutRecv && !isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                        absorbElementIntoLocalContainer(recvOrig.uniqueOwner, argOrigins[i]);
                    }
                    if (!uniqueP && (argOrigins[i].unique || argOrigins[i].localFresh) && argOrigins[i].uniqueOwner != null) {
                        demoteUniqueOwner(argOrigins[i].uniqueOwner, Origin.IMMUTABLE);
                    }
                    markStoredOrigin(argOrigins[i], "Java call argument", argExprs[i]);
                }
                // Java SAM callback static effect propagation (§6.3):
                Class<?> javaParamClass = null;
                if (genericParamTypes != null && pIdx < genericParamTypes.length) {
                    java.lang.reflect.Type gp = genericParamTypes[pIdx];
                    if (varArgs && i >= fixed) {
                        if (gp instanceof java.lang.reflect.GenericArrayType gat) {
                            gp = gat.getGenericComponentType();
                        } else if (gp instanceof Class<?> c && c.isArray()) {
                            gp = c.getComponentType();
                        }
                    }
                    javaParamClass = samInterfaceForJavaType(gp);
                } else if (javaParamTypes != null && pIdx < javaParamTypes.length) {
                    javaParamClass = (varArgs && i >= fixed && javaParamTypes[fixed] != null && javaParamTypes[fixed].isArray())
                            ? javaParamTypes[fixed].getComponentType()
                            : javaParamTypes[pIdx];
                }
                if (javaParamClass != null && JavaTypes.isFunctionalInterface(javaParamClass)) {
                    propagateSamCallbackEffects(argType, javaParamClass);
                }
            }

            // Static call-site mut exclusivity:
            checkCallExclusivity(receiverExpr, recvOrig, effectiveMutRecv, argExprs, argOrigins, argIsMut, call);

            // Result origin (BIP019 §4.1: deterministic callees return fresh or argument/receiver-derived results):
            if (isDeeplyImmutable(resType)) {
                return Origin.IMMUTABLE;
            }
            if (profile.fresh() || profile.effects().uniqueResult()) {
                return Origin.ofUnique(call, null, false);
            }
            if (receiverExpr != null && profile.view()) {
                return recvOrig.deriveMember("$" + methodName, false);
            }
            boolean deterministicCall = profile.effects().isHermetic() && profile.effects().isDeterministic();
            if ("<init>".equals(methodName) || isCollectionFactoryMethod(owner, methodName)) {
                if (deterministicCall) {
                    Origin fresh = Origin.ofUnique(call, null, false);
                    boolean staysUnique = true;
                    for (int i = 0; i < argOrigins.length; i++) {
                        Type argType = effectiveType(argExprs[i]);
                        if (!isDeeplyImmutable(argType) && !isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                            staysUnique = false;
                            fresh = fresh.absorbElement(argOrigins[i]);
                        }
                    }
                    return staysUnique ? Origin.ofUnique(call, null, false) : fresh;
                }
                return Origin.ofExternal(call, false);
            }
            if (deterministicCall) {
                Origin out = Origin.IMMUTABLE;
                if (receiverExpr != null && recvOrig != Origin.IMMUTABLE) {
                    Origin flatRecv = recvOrig.hasElementTaint() ? recvOrig.flattenElements() : recvOrig;
                    out = flatRecv.stripDirectIdentity(false);
                }
                for (int i = 0; i < argOrigins.length; i++) {
                    if (argOrigins[i] != Origin.IMMUTABLE) {
                        Origin flatArg = argOrigins[i].hasElementTaint() ? argOrigins[i].flattenElements() : argOrigins[i];
                        Origin stripped = flatArg.stripDirectIdentity(false);
                        out = out == Origin.IMMUTABLE ? stripped : out.join(stripped);
                    }
                }
                if (out == Origin.IMMUTABLE) {
                    return Origin.ofUnique(call, null, false);
                }
                if (out.unique || out.localFresh) {
                    out = out.withoutUnique();
                }
                return out;
            }
            Origin out = Origin.ofExternal(call, false);
            if (receiverExpr != null) {
                out = out.join(recvOrig.stripDirectIdentity(false));
            }
            for (int i = 0; i < argOrigins.length; i++) {
                boolean borrowP = i < profile.borrowParams().length && profile.borrowParams()[i];
                if (!borrowP) {
                    out = out.join(argOrigins[i].stripDirectIdentity(false));
                }
            }
            return out;
        }

        private static boolean isCollectionFactoryMethod(Class<?> owner, String methodName) {
            if (owner == null || methodName == null) {
                return false;
            }
            if (owner == java.util.List.class || owner == java.util.Set.class || owner == java.util.Map.class) {
                return "of".equals(methodName) || "copyOf".equals(methodName);
            }
            if (owner == java.util.Arrays.class) {
                return "asList".equals(methodName);
            }
            if (owner == java.util.Collections.class) {
                return "nCopies".equals(methodName) || "singletonList".equals(methodName)
                        || "singleton".equals(methodName) || "singletonMap".equals(methodName)
                        || "emptyList".equals(methodName) || "emptySet".equals(methodName)
                        || "emptyMap".equals(methodName);
            }
            return false;
        }

        private void propagateSamCallbackEffects(Type argType, Class<?> samClass) {
            for (Type m : Type.membersOf(argType)) {
                if (m instanceof Type.Function fn) {
                    BabbleParser.FunctionLiteralContext lit = checker.literalOf(fn);
                    if (lit != null && summaries.containsKey(lit)) {
                        applyCallableSummaryEffects(summaries.get(lit));
                    } else {
                        applyEffects(fn.effects());
                    }
                } else if (m instanceof Type.Instance inst) {
                    Method sam = JavaTypes.functionalMethod(samClass);
                    if (sam != null) {
                        TypeNode.Member impl = checker.findMemberOnNode(inst.node(), sam.getName());
                        if (impl != null && impl.function != null && summaries.containsKey(impl.function)) {
                            applyCallableSummaryEffects(summaries.get(impl.function));
                        } else if (impl != null && checker.memberTypeForEffects(inst.node(), impl) instanceof Type.Function tf) {
                            applyEffects(tf.effects());
                        } else {
                            hermetic = false;
                            deterministic = false;
                            nothrow = false;
                        }
                    }
                } else if (m != Type.ERROR) {
                    hermetic = false;
                    deterministic = false;
                    nothrow = false;
                }
            }
        }

        private Origin applyBabbleCallableType(ParserRuleContext call,
                                               BabbleParser.ExpressionContext calleeExpr,
                                               Type calleeType,
                                               BabbleParser.ExpressionContext receiverExpr,
                                               Origin recvOrig,
                                               List<BabbleParser.CallParameterContext> args,
                                               BabbleParser.ExpressionContext[] argExprs,
                                               Origin[] argOrigins,
                                               Type resType) {
            List<Type> members = Type.membersOf(calleeType);
            Origin combinedOut = Origin.IMMUTABLE;
            for (Type member : members) {
                if (!(member instanceof Type.Function fn)) {
                    continue;
                }
                Origin memberOut = applyBabbleCallableMember(call, calleeExpr, fn, null,
                        receiverExpr, recvOrig, args, argExprs, argOrigins, resType);
                combinedOut = combinedOut == Origin.IMMUTABLE ? memberOut : combinedOut.join(memberOut);
            }
            return combinedOut;
        }

        private Origin applyBabbleCallableMember(ParserRuleContext call,
                                                 BabbleParser.ExpressionContext calleeExpr,
                                                 Type.Function fn,
                                                 ScopeAnalysis.SpreadAdapt[] explicitAdapts,
                                                 BabbleParser.ExpressionContext receiverExpr,
                                                 Origin recvOrig,
                                                 List<BabbleParser.CallParameterContext> args,
                                                 BabbleParser.ExpressionContext[] argExprs,
                                                 Origin[] argOrigins,
                                                 Type resType) {
            CallableSummary summary = resolveBabbleCallSummary(fn, calleeExpr, receiverExpr, args, argExprs);
            applyCallableSummaryEffects(summary);
            boolean isMethodCall = receiverExpr != null || calleeExpr instanceof BabbleParser.SuperExpressionContext
                    || (calleeExpr instanceof BabbleParser.SelectorContext s && s.expression() instanceof BabbleParser.SuperExpressionContext);
            Origin effectiveRecvOrig = (calleeExpr instanceof BabbleParser.SelectorContext s && s.expression() instanceof BabbleParser.SuperExpressionContext)
                    ? Origin.ofThis(THIS_KEY, false) : recvOrig;
            boolean effectiveBorrowRecv = !isMethodCall || summary.effects().borrowThis()
                    || (summary.effects().isHermetic() && !anyTrue(summary.mutParams()) && isDeeplyImmutable(resType));
            if (isMethodCall && summary.readsThis()) {
                Type rType = receiverExpr != null ? effectiveType(receiverExpr) : Type.ANY;
                recordTargetRead(effectiveRecvOrig, rType);
            }
            boolean[] calleeRead = summary.readParams();
            for (int i = 0; i < args.size(); i++) {
                int pIdx = checker.parameterIndexForArg(fn.parameters(), args, args.get(i));
                if (pIdx >= 0 && pIdx < calleeRead.length && calleeRead[pIdx]) {
                    recordTargetRead(argOrigins[i], effectiveType(argExprs[i]));
                }
            }
            boolean[] mutableCopiedSpread = new boolean[args.size()];
            Origin[] postSpreadOrigins = applyBabbleArgEffects(call, calleeExpr, fn.parameters(), summary.mutParams(), summary.borrowParams(), summary.uniqueParams(),
                    isMethodCall ? receiverExpr : null, effectiveRecvOrig,
                    isMethodCall && summary.effects().mutThis(),
                    summary.deepMutThis(),
                    effectiveBorrowRecv,
                    summary.effects().isHermetic(),
                    args, argExprs, argOrigins, explicitAdapts, mutableCopiedSpread);
            if (!fn.forwardedEffectParams().isEmpty()) {
                applyForwardedCallbackEffects(call, fn, isMethodCall ? receiverExpr : null, effectiveRecvOrig,
                        args, argExprs, argOrigins);
            }
            if (summary.effects().uniqueResult()) {
                return Origin.ofUnique(call, null, isDeeplyImmutable(resType));
            }
            return deriveCallReturnOrigin(call, resType, summary, isMethodCall, effectiveRecvOrig,
                    argOrigins, postSpreadOrigins, mutableCopiedSpread, fn.parameters(), args);
        }

        private boolean isForwardedParamExpr(BabbleParser.ExpressionContext expr) {
            BabbleParser.ExpressionContext unwrapped = unwrapParens(expr);
            if (!(unwrapped instanceof BabbleParser.SelectorContext s) || s.expression() != null) {
                return false;
            }
            Object resolved = checker.resolveNameForEffects(s.ID().getText(), checker.occurrenceScopeOf(s));
            if (!(resolved instanceof ParserRuleContext decl)) {
                return false;
            }
            Integer idx = paramIndex.get(decl);
            if (idx != null && forwardedParams.contains(idx)) {
                return true;
            }
            if (decl instanceof BabbleParser.FnParamContext param) {
                if (owner instanceof BabbleParser.FunctionLiteralContext ownerFn && capturesCallback(ownerFn, param)) {
                    return true;
                }
                for (BabbleParser.FunctionLiteralContext encFn : frameChain) {
                    List<BabbleParser.FnParamContext> encParams = encFn.fnParameters().fnParam();
                    int encIdx = encParams.indexOf(decl);
                    if (encIdx >= 0 && resolveForwardedParamIndices(encFn).contains(encIdx)
                            || capturesCallback(encFn, param)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * The effects of calling a function value that still forwards captured callbacks (inside
         * the function that captures them, before a call instantiates them, BIP017-D5) : those of
         * each callback, unless the current function forwards it in turn.
         */
        private EffectSet withCapturedCallbackEffects(Type.Function fn, BabbleParser.ExpressionContext calleeExpr) {
            EffectSet effects = fn.effects();
            for (String name : fn.capturedCallings()) {
                Object resolved = checker.resolveNameForEffects(name, checker.occurrenceScopeOf(calleeExpr));
                if (resolved instanceof BabbleParser.FnParamContext param) {
                    if (forwardsParameter(param)) {
                        stmtHasSideEffect = true;
                        continue;
                    }
                    if (checker.typeOfDeclarationForEffects(param) instanceof Type.Function cb) {
                        effects = effects.combineForwarded(cb.effects());
                        continue;
                    }
                }
                effects = effects.combineForwarded(EffectSet.IO_DECLARED);
            }
            return effects;
        }

        private boolean forwardsParameter(BabbleParser.FnParamContext param) {
            Integer idx = paramIndex.get(param);
            if (idx != null && forwardedParams.contains(idx)) {
                return true;
            }
            if (owner instanceof BabbleParser.FunctionLiteralContext ownerFn && capturesCallback(ownerFn, param)) {
                return true;
            }
            for (BabbleParser.FunctionLiteralContext encFn : frameChain) {
                int encIdx = encFn.fnParameters().fnParam().indexOf(param);
                if (encIdx >= 0 && resolveForwardedParamIndices(encFn).contains(encIdx) || capturesCallback(encFn, param)) {
                    return true;
                }
            }
            return false;
        }

        private void applyForwardedCallbackEffects(ParserRuleContext call,
                                                   Type.Function hof,
                                                   BabbleParser.ExpressionContext receiverExpr,
                                                   Origin recvOrig,
                                                   List<BabbleParser.CallParameterContext> args,
                                                   BabbleParser.ExpressionContext[] argExprs,
                                                   Origin[] argOrigins) {
            for (int i = 0; i < args.size(); i++) {
                int pIdx = checker.parameterIndexForArg(hof.parameters(), args, args.get(i));
                if (!hof.forwardedEffectParams().contains(pIdx)) {
                    continue;
                }
                BabbleParser.ExpressionContext argExpr = argExprs[i];
                if (isForwardedParamExpr(argExpr)) {
                    stmtHasSideEffect = true;
                    continue;
                }
                Type argType = effectiveType(argExpr);
                for (Type m : Type.membersOf(argType)) {
                    if (!(m instanceof Type.Function cbFn)) {
                        continue;
                    }
                    EffectSet cbEffects;
                    BabbleParser.FunctionLiteralContext cbLit = checker.literalOf(cbFn);
                    if (cbLit != null && summaries.containsKey(cbLit)) {
                        CallableSummary cbSum = summaries.get(cbLit);
                        applyCallableSummaryEffects(cbSum);
                        cbEffects = cbSum.effects();
                    } else {
                        applyEffects(cbFn.effects());
                        cbEffects = cbFn.effects();
                    }
                    if (!cbEffects.isHermetic() || !cbEffects.isDeterministic()) {
                        stmtHasSideEffect = true;
                    }
                    if (cbEffects.mutThis()) {
                        BabbleParser.ExpressionContext unwrapped = unwrapParens(argExpr);
                        if (unwrapped instanceof BabbleParser.SelectorContext s && s.expression() != null) {
                            Origin boundRecvOrig = boundMethodReceivers.get(s);
                            if (boundRecvOrig != null) {
                                if (containsFrozen(effectiveType(s.expression()))) {
                                    reportOnce("Cannot call mut method on frozen value", call);
                                } else {
                                    recordTargetMutation(boundRecvOrig, s.expression(),
                                            "forwarded mut callback " + s.getText(), call, false);
                                }
                                if (receiverExpr != null && staticallySameObject(boundRecvOrig, recvOrig)) {
                                    reportOnce("Exclusivity error : mut target " + s.expression().getText()
                                            + " aliases argument " + receiverExpr.getText(), call);
                                }
                                for (int j = 0; j < argExprs.length; j++) {
                                    if (j != i && staticallySameObject(boundRecvOrig, argOrigins[j])) {
                                        reportOnce("Exclusivity error : mut target " + s.expression().getText()
                                                + " aliases argument " + argExprs[j].getText(), argExprs[j]);
                                    }
                                }
                            } else {
                                hermetic = false;
                                deterministic = false;
                                stmtHasSideEffect = true;
                            }
                        } else {
                            hermetic = false;
                            deterministic = false;
                            stmtHasSideEffect = true;
                        }
                    }
                }
            }
        }

        private CallableSummary resolveBabbleCallSummary(Type.Function fn,
                                                         BabbleParser.ExpressionContext calleeExpr,
                                                         BabbleParser.ExpressionContext receiverExpr,
                                                         List<BabbleParser.CallParameterContext> args,
                                                         BabbleParser.ExpressionContext[] argExprs) {
            // Forwarded callback parameter inside a `fn calling f` body: pure-modulo-forwarding (BIP017-D1).
            if (isForwardedParamExpr(calleeExpr)) {
                stmtHasSideEffect = true;
                int n = fn.parameters().size();
                boolean[] mp = new boolean[n];
                boolean[] bp = new boolean[n];
                boolean[] up = new boolean[n];
                for (int i = 0; i < n; i++) {
                    mp[i] = fn.parameters().get(i).mut();
                    bp[i] = fn.parameters().get(i).borrow();
                    up[i] = fn.parameters().get(i).unique();
                }
                EffectSet pureModForward = EffectSet.ofInferred(
                        EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                        false, true, fn.effects().uniqueResult(), true);
                return new CallableSummary(pureModForward, mp, bp, up);
            }

            // Built-ins (assert, same, classof, int, double):
            if (checker.isBuiltinFunction(fn)) {
                int n = fn.parameters().size();
                boolean[] mp = new boolean[n];
                boolean[] bp = new boolean[n];
                boolean[] up = new boolean[n];
                for (int i = 0; i < n; i++) {
                    mp[i] = fn.parameters().get(i).mut();
                    bp[i] = fn.parameters().get(i).borrow();
                    up[i] = fn.parameters().get(i).unique();
                }
                return new CallableSummary(fn.effects(), mp, bp, up);
            }

            // Statically resolved concrete Babble function literal (intra-file):
            BabbleParser.FunctionLiteralContext targetLit = resolveConcreteBabbleTarget(fn, calleeExpr, receiverExpr);
            if (targetLit != null && capturesCallbacks(targetLit)) {
                // Its summary takes the captured callbacks as pure : the type has their effects (BIP017-D5).
                targetLit = null;
            } else if (targetLit != null) {
                ParsedPrefix targetPrefix = parseFunctionPrefix(targetLit, false);
                // BIP017-D4: retire BIP016-D6 implicit intra-file `fn io` HOF specialization;
                // `fn calling f` uses uniform call-site forwarding across concrete, cross-module, and interface HOFs.
                if (!targetPrefix.axiom() && !targetPrefix.explicitVolatile() && !targetPrefix.io()
                        && fn.forwardedEffectParams().isEmpty()) {
                    CallableSummary specialized = trySpecializeCall(targetLit, fn.parameters(), args, argExprs, EffectChecker.this);
                    if (specialized != null) {
                        return specialized;
                    }
                }
                CallableSummary base = summaries.get(targetLit);
                if (base != null) {
                    if (targetLit != owner && (targetPrefix.io() || targetPrefix.explicitVolatile())) {
                        CallableSummary vis = visibleSummaries.get(targetLit);
                        return vis != null ? vis : buildVisibleSummary(targetLit, base);
                    }
                    return visibleSummaries.getOrDefault(targetLit, base);
                }
            } else if (!(receiverExpr != null && effectiveType(receiverExpr) instanceof Type.Instance inst && inst.node().isInterface())) {
                TypedModuleSummary.CallableEffectTemplate template = checker.remoteCallableTemplate(fn);
                if (template != null) {
                    Type.Function[] boundCallbacks = collectBoundCallbacks(fn.parameters(), args, argExprs);
                    return template.specialize(boundCallbacks);
                }
            }

            // Abstract interface method, parameter callback, or external Type.Function signature:
            int n = fn.parameters().size();
            boolean[] mp = new boolean[n];
            boolean[] bp = new boolean[n];
            boolean[] up = new boolean[n];
            boolean[] rp = new boolean[n];
            for (int i = 0; i < n; i++) {
                Type.Parameter p = fn.parameters().get(i);
                mp[i] = p.mut();
                bp[i] = p.borrow();
                up[i] = p.unique();
                rp[i] = !isDeeplyImmutable(p.type());
            }
            EffectSet effects = fn.capturedCallings().isEmpty() ? fn.effects() : withCapturedCallbackEffects(fn, calleeExpr);
            return new CallableSummary(effects, mp, bp, up, Set.of(), Set.of(), effects.mutThis(),
                    EMPTY_SUMMARY_BITS, false, false, false, EMPTY_SUMMARY_BITS, rp, true);
        }

        private Type.Function[] collectBoundCallbacks(List<Type.Parameter> calleeParams,
                                                      List<BabbleParser.CallParameterContext> args,
                                                      BabbleParser.ExpressionContext[] argExprs) {
            Type.Function[] result = new Type.Function[calleeParams.size()];
            for (int i = 0; i < args.size(); i++) {
                int pIdx = checker.parameterIndexForArg(calleeParams, args, args.get(i));
                if (pIdx < 0 || pIdx >= result.length) {
                    continue;
                }
                Type argType = checker.rawTypeOf(argExprs[i]);
                if (argType instanceof Type.Function argFn) {
                    BabbleParser.FunctionLiteralContext argLit = checker.literalOf(argFn);
                    if (argLit != null) {
                        CallableSummary argSum = summaries.get(argLit);
                        if (argSum != null) {
                            argFn = enrichFunctionType(argFn, argSum);
                        }
                    }
                    result[pIdx] = argFn;
                }
            }
            return result;
        }

        private BabbleParser.FunctionLiteralContext resolveConcreteBabbleTarget(Type.Function fn,
                                                                                BabbleParser.ExpressionContext calleeExpr,
                                                                                BabbleParser.ExpressionContext receiverExpr) {
            if (receiverExpr != null) {
                Type recvType = effectiveType(receiverExpr);
                if (recvType instanceof Type.Instance inst && inst.node().isInterface()) {
                    // Abstract or overridable interface dispatch cannot be specialized statically (§4.5)!
                    return null;
                }
            }
            return checker.literalOf(fn);
        }

        private Origin[] applyBabbleArgEffects(ParserRuleContext call,
                                               BabbleParser.ExpressionContext calleeExpr,
                                               List<Type.Parameter> calleeParams,
                                               boolean[] calleeMut,
                                               boolean[] calleeBorrow,
                                               boolean[] calleeUnique,
                                               BabbleParser.ExpressionContext receiverExpr,
                                               Origin recvOrig,
                                               boolean mutRecv,
                                               boolean deepMutRecv,
                                               boolean borrowRecv,
                                               boolean calleeHermetic,
                                               List<BabbleParser.CallParameterContext> args,
                                               BabbleParser.ExpressionContext[] argExprs,
                                               Origin[] argOrigins) {
            return applyBabbleArgEffects(call, calleeExpr, calleeParams, calleeMut, calleeBorrow, calleeUnique,
                    receiverExpr, recvOrig, mutRecv, deepMutRecv, borrowRecv, calleeHermetic,
                    args, argExprs, argOrigins, null, null);
        }

        private Origin[] applyBabbleArgEffects(ParserRuleContext call,
                                               BabbleParser.ExpressionContext calleeExpr,
                                               List<Type.Parameter> calleeParams,
                                               boolean[] calleeMut,
                                               boolean[] calleeBorrow,
                                               boolean[] calleeUnique,
                                               BabbleParser.ExpressionContext receiverExpr,
                                               Origin recvOrig,
                                               boolean mutRecv,
                                               boolean deepMutRecv,
                                               boolean borrowRecv,
                                               boolean calleeHermetic,
                                               List<BabbleParser.CallParameterContext> args,
                                               BabbleParser.ExpressionContext[] argExprs,
                                               Origin[] argOrigins,
                                               ScopeAnalysis.SpreadAdapt[] explicitAdapts,
                                               boolean[] outMutableCopiedSpread) {
            if (receiverExpr != null && containsFrozen(effectiveType(receiverExpr))) {
                if (mutRecv) {
                    reportOnce("Cannot call mut method on frozen value", call);
                } else if (!borrowRecv) {
                    reportOnce("Cannot call shared method on frozen value", call);
                }
            }
            boolean effectiveMutRecv = mutRecv && (receiverExpr != null || !recvOrig.isEmpty())
                    && (receiverExpr == null || effectiveType(receiverExpr) != Type.STRING);
            if (effectiveMutRecv) {
                recordTargetMutation(recvOrig, receiverExpr != null ? receiverExpr : call,
                        "mut method " + calleeExpr.getText(), call, !deepMutRecv);
            }
            if (!borrowRecv) {
                if ((recvOrig.unique || recvOrig.localFresh) && recvOrig.uniqueOwner != null) {
                    demoteUniqueOwner(recvOrig.uniqueOwner, Origin.IMMUTABLE);
                }
                markStoredOrigin(recvOrig, "method call " + calleeExpr.getText(), call);
            }

            boolean localFreshRecvOnly = calleeHermetic && effectiveMutRecv
                    && (recvOrig.unique || recvOrig.localFresh)
                    && !recvOrig.external && recvOrig.params.isEmpty() && !recvOrig.aliasesThis
                    && !anyTrue(calleeMut);
            boolean[] argIsExclusive = new boolean[args.size()];
            Origin[] postSpreadOrigins = new Origin[args.size()];
            for (int i = 0; i < args.size(); i++) {
                int pIdx = checker.parameterIndexForArg(calleeParams, args, args.get(i));
                Type argType = effectiveType(argExprs[i]);
                Origin effectiveArgOrig = argOrigins[i];
                boolean copiedSpread = false;
                boolean mutCopiedSpread = false;
                if (ScopeAnalysis.isSpread(args.get(i))) {
                    recordTargetRead(argOrigins[i], argType);
                    ScopeAnalysis.SpreadAdapt adapt = explicitAdapts != null
                            ? (i < explicitAdapts.length ? explicitAdapts[i] : null)
                            : checker.spreadAdapt(call, i);
                    if (adapt != null && (adapt.kind() == ScopeAnalysis.SpreadKind.TO_LIST_COPY
                            || adapt.kind() == ScopeAnalysis.SpreadKind.TO_ARRAY)) {
                        copiedSpread = true;
                        Type elemType = checker.extractSequenceElement(argType);
                        if ((elemType != null && isDeeplyImmutable(elemType))
                                || isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                            effectiveArgOrig = Origin.ofUnique(argExprs[i], null, false);
                        } else {
                            mutCopiedSpread = true;
                            effectiveArgOrig = Origin.ofUnique(argExprs[i], null, false).absorbElement(argOrigins[i]);
                        }
                    }
                }
                if (outMutableCopiedSpread != null && i < outMutableCopiedSpread.length) {
                    outMutableCopiedSpread[i] = mutCopiedSpread;
                }
                postSpreadOrigins[i] = effectiveArgOrig;
                boolean mutP = pIdx >= 0 && pIdx < calleeMut.length && calleeMut[pIdx] && argType != Type.STRING;
                boolean borrowP = pIdx >= 0 && pIdx < calleeBorrow.length && calleeBorrow[pIdx];
                boolean uniqueP = pIdx >= 0 && pIdx < calleeUnique.length && calleeUnique[pIdx];
                argIsExclusive[i] = mutP;
                if (uniqueP && !effectiveArgOrig.moved) {
                    String pName = pIdx >= 0 && pIdx < calleeParams.size() ? calleeParams.get(pIdx).name() : ("#" + i);
                    if (!copiedSpread && containsFrozen(argType) && effectiveArgOrig == argOrigins[i]) {
                        reportOnce("Argument for unique parameter " + pName + " cannot be frozen", argExprs[i]);
                    } else if (!effectiveArgOrig.unique && effectiveArgOrig != Origin.IMMUTABLE && !isDeeplyImmutable(argType) && argType != Type.ERROR) {
                        reportOnce("Argument for unique parameter " + pName + " must be unique", argExprs[i]);
                    } else if (!borrowP && effectiveArgOrig.unique && effectiveArgOrig.uniqueOwner instanceof ParserRuleContext decl) {
                        if (paramIndex.containsKey(decl) && borrowParams[paramIndex.get(decl)]) {
                            reportOnce("Cannot move borrowed parameter " + argExprs[i].getText(), argExprs[i]);
                        } else {
                            env.put(decl, Origin.MOVED);
                        }
                    }
                }
                if (mutP) {
                    recordTargetMutation(effectiveArgOrig, argExprs[i],
                            "mut parameter " + calleeParams.get(pIdx).name(), argExprs[i], false);
                }
                if (!borrowP) {
                    if ((recvOrig.unique || recvOrig.localFresh) && effectiveMutRecv && !isUnattachedUniqueOrImmutable(effectiveArgOrig, argType)) {
                        absorbElementIntoLocalContainer(recvOrig.uniqueOwner, effectiveArgOrig);
                    }
                    if (!uniqueP && (effectiveArgOrig.unique || effectiveArgOrig.localFresh) && effectiveArgOrig.uniqueOwner != null) {
                        demoteUniqueOwner(effectiveArgOrig.uniqueOwner, Origin.IMMUTABLE);
                    }
                    if (mutCopiedSpread && (argOrigins[i].unique || argOrigins[i].localFresh) && argOrigins[i].uniqueOwner != null) {
                        demoteUniqueOwner(argOrigins[i].uniqueOwner, Origin.IMMUTABLE);
                    }
                    if (!localFreshRecvOnly) {
                        markStoredOrigin(effectiveArgOrig, "call to " + calleeExpr.getText(), argExprs[i]);
                    }
                }
            }

            checkCallExclusivity(receiverExpr, recvOrig, effectiveMutRecv, argExprs, argOrigins, argIsExclusive, call);
            return postSpreadOrigins;
        }

        private Origin deriveCallReturnOrigin(ParserRuleContext call,
                                              Type resType,
                                              CallableSummary summary,
                                              boolean isMethodCall,
                                              Origin recvOrig,
                                              Origin[] rawArgOrigins,
                                              Origin[] argOrigins,
                                              boolean[] mutableCopiedSpread,
                                              List<Type.Parameter> calleeParams,
                                              List<BabbleParser.CallParameterContext> args) {
            if (isDeeplyImmutable(resType)) {
                return Origin.IMMUTABLE;
            }
            boolean resultDiscarded = call != null && call == discardedExpr;
            boolean known = summary.returnKnown();
            boolean deterministicCallee = summary.effects().isHermetic() && summary.effects().isDeterministic();
            boolean includeExternal = known ? summary.returnExternal() : !deterministicCallee;
            Origin out = includeExternal ? Origin.ofExternal(null, false) : Origin.IMMUTABLE;
            boolean includeRecv = isMethodCall && (!known || summary.returnThis() || !summary.effects().borrowThis());
            if (includeRecv && recvOrig != Origin.IMMUTABLE) {
                if (!resultDiscarded && (recvOrig.unique || recvOrig.localFresh) && recvOrig.uniqueOwner != null) {
                    demoteUniqueOwner(recvOrig.uniqueOwner, Origin.IMMUTABLE);
                }
                Origin flatRecv = recvOrig.hasElementTaint() ? recvOrig.flattenElements() : recvOrig;
                out = out == Origin.IMMUTABLE ? flatRecv : out.join(flatRecv);
            }
            boolean[] calleeBorrow = summary.borrowParams();
            for (int i = 0; i < args.size(); i++) {
                int pIdx = checker.parameterIndexForArg(calleeParams, args, args.get(i));
                boolean borrowP = pIdx >= 0 && pIdx < calleeBorrow.length && calleeBorrow[pIdx];
                boolean returnedP = !known || (pIdx >= 0 && summary.returnParams().get(pIdx)) || !borrowP;
                if (returnedP && argOrigins[i] != Origin.IMMUTABLE) {
                    if (!resultDiscarded && (argOrigins[i].unique || argOrigins[i].localFresh) && argOrigins[i].uniqueOwner != null) {
                        demoteUniqueOwner(argOrigins[i].uniqueOwner, Origin.IMMUTABLE);
                    }
                    if (!resultDiscarded && mutableCopiedSpread != null && i < mutableCopiedSpread.length
                            && mutableCopiedSpread[i]
                            && (rawArgOrigins[i].unique || rawArgOrigins[i].localFresh)
                            && rawArgOrigins[i].uniqueOwner != null) {
                        demoteUniqueOwner(rawArgOrigins[i].uniqueOwner, Origin.IMMUTABLE);
                    }
                    Origin flatArg = argOrigins[i].hasElementTaint() ? argOrigins[i].flattenElements() : argOrigins[i];
                    out = out == Origin.IMMUTABLE ? flatArg : out.join(flatArg);
                }
            }
            if (out == Origin.IMMUTABLE) {
                return Origin.ofUnique(call, null, false);
            }
            if (known && summary.effects().isHermetic() && summary.effects().isDeterministic()
                    && !anyTrue(summary.mutParams()) && !(isMethodCall && summary.effects().mutThis())
                    && summary.frameWriteOwners().isEmpty()
                    && out.localFresh && out.uniqueOwner == null
                    && out.params.isEmpty() && !out.aliasesThis && !out.external) {
                return new Origin(new BitSet(0), false, false, false, true, false, null,
                        (BitSet) out.elementParams.clone(), out.elementThis, out.elementExternal,
                        Set.of(), null);
            }
            if (out.unique || out.localFresh) {
                out = out.withoutUnique();
            }
            return out;
        }

        private void checkCallExclusivity(BabbleParser.ExpressionContext receiverExpr,
                                          Origin recvOrig,
                                          boolean mutRecv,
                                          BabbleParser.ExpressionContext[] argExprs,
                                          Origin[] argOrigins,
                                          boolean[] argIsMut,
                                          ParserRuleContext call) {
            int total = (receiverExpr != null ? 1 : 0) + argExprs.length;
            if (total < 2) {
                return;
            }
            Origin[] origins = new Origin[total];
            boolean[] muts = new boolean[total];
            ParserRuleContext[] sites = new ParserRuleContext[total];
            String[] labels = new String[total];
            int offset = 0;
            if (receiverExpr != null) {
                origins[0] = effectiveType(receiverExpr) == Type.STRING ? Origin.IMMUTABLE : recvOrig;
                muts[0] = mutRecv;
                sites[0] = receiverExpr;
                labels[0] = receiverExpr.getText();
                offset = 1;
            }
            for (int i = 0; i < argExprs.length; i++) {
                origins[offset + i] = effectiveType(argExprs[i]) == Type.STRING ? Origin.IMMUTABLE : argOrigins[i];
                muts[offset + i] = argIsMut[i];
                sites[offset + i] = argExprs[i];
                labels[offset + i] = argExprs[i].getText();
            }
            for (int i = 0; i < total; i++) {
                if (!muts[i]) {
                    continue;
                }
                for (int j = 0; j < total; j++) {
                    if (i == j) {
                        continue;
                    }
                    if (staticallySameObject(origins[i], origins[j])) {
                        reportOnce("Exclusivity error : mut target " + labels[i] + " aliases argument " + labels[j], sites[Math.max(i, j)]);
                        return;
                    }
                }
            }
        }

        private boolean staticallySameObject(Origin a, Origin b) {
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
            return a.pathKey != null && a.pathKey.equals(b.pathKey);
        }

        private void recordTargetMutation(Origin target, ParserRuleContext targetExpr, String reason,
                                          ParserRuleContext at, boolean shallow) {
            stmtHasSideEffect = true;
            if (targetExpr instanceof BabbleParser.ExpressionContext expr && effectiveType(expr) == Type.ERROR) {
                return;
            }
            if (!activeForIterables.isEmpty() && isConstrainedPureOrHermetic() && !target.directKeys.isEmpty()) {
                for (Origin iterOrig : activeForIterables) {
                    for (Object k : target.directKeys) {
                        if (iterOrig.directKeys.contains(k)) {
                            reportOnce("Cannot mutate collection while iterating over it", at);
                            break;
                        }
                    }
                }
            }
            // In BIP007-I2 & BIP016-D9, shallow mutation of a fresh local container (or any mutation of a unique local/param)
            // stays confined (hermetic/pure) without tainting stored elements!
            if ((target.unique || (target.localFresh && shallow))
                    && !target.external && target.params.isEmpty() && !target.aliasesThis) {
                return;
            }
            Origin effectiveTarget = (!shallow && target.hasElementTaint()) ? target.flattenElements() : target;
            boolean hitParamOrThis = false;
            if (effectiveTarget.aliasesThis) {
                hitParamOrThis = true;
                if (!shallow || !effectiveTarget.directKeys.contains(THIS_KEY)) {
                    deepMutThis = true;
                }
                if (constructingSelf) {
                    // Allowed: initializing `this` inside constructor.
                } else if (mutThis) {
                    // Explicitly declared or already inferred mutThis.
                } else if (inferMut) {
                    mutThis = true;
                } else {
                    reportOnce("Cannot mutate this (" + reason + ") : function is not declared mut", at);
                }
            }
            for (int p = effectiveTarget.params.nextSetBit(0); p >= 0; p = effectiveTarget.params.nextSetBit(p + 1)) {
                if (p >= params.size()) {
                    continue; // a captured callback (pseudo-parameter) : outer state
                }
                hitParamOrThis = true;
                if (mutParams[p]) {
                    // Explicitly declared or already inferred mut parameter.
                } else if (inferMut) {
                    mutParams[p] = true;
                } else {
                    String pName = ScopeAnalysis.paramId(params.get(p)).getText();
                    reportOnce("Cannot mutate parameter " + pName + " (" + reason + ") : mark parameter as mut " + pName, at);
                }
            }
            if (effectiveTarget.external || !hitParamOrThis) {
                hermetic = false;
                if (isConstrainedPureOrHermetic()) {
                    reportOnce("Cannot mutate external object in "
                            + (declaredPrefix.explicitVolatile() ? "volatile" : "pure")
                            + " function (" + reason + ") : mark function as io", at);
                }
            }
        }

        private void markStoredIntoTarget(Origin target, Origin value, Type valueType, String fieldName, ParserRuleContext at) {
            if (value == Origin.IMMUTABLE) {
                return;
            }
            if ((target.unique || target.localFresh) && !target.external && target.params.isEmpty() && !target.aliasesThis) {
                // Storing into a fresh local container: absorb element provenance without escaping yet!
                if (!isUnattachedUniqueOrImmutable(value, valueType)) {
                    absorbElementIntoLocalContainer(target.uniqueOwner, value);
                    if ((value.unique || value.localFresh) && value.uniqueOwner != null) {
                        demoteUniqueOwner(value.uniqueOwner, Origin.IMMUTABLE);
                    }
                }
                return;
            }
            if (constructingSelf && target.aliasesThis && target.params.isEmpty() && !target.external) {
                // Storing into `this`'s own fields during construction stays confined inside the new object!
                Origin flat = value.hasElementTaint() ? value.flattenElements() : value;
                ctorStoredParams.or(flat.params);
                if ((value.unique || value.localFresh) && value.uniqueOwner != null) {
                    demoteUniqueOwner(value.uniqueOwner, Origin.IMMUTABLE);
                }
                return;
            }
            if ((value.unique || value.localFresh) && value.uniqueOwner != null) {
                demoteUniqueOwner(value.uniqueOwner, Origin.IMMUTABLE);
            }
            // Storing `this` (or a sub-reference of `this`) back into `this`'s own field does not escape `this`!
            boolean storingIntoSelfThis = target.aliasesThis && target.params.isEmpty() && !target.external;
            markStoredOrigin(value, "field ." + fieldName, at, storingIntoSelfThis);
        }

        private void markStoredOrigin(Origin value, String destination, ParserRuleContext at) {
            markStoredOrigin(value, destination, at, false);
        }

        private void markStoredOrigin(Origin value, String destination, ParserRuleContext at, boolean ignoreThis) {
            if (value == Origin.IMMUTABLE) {
                return;
            }
            Origin flat = value.hasElementTaint() ? value.flattenElements() : value;
            if (flat.aliasesThis && !ignoreThis) {
                if (borrowThis) {
                    borrowThis = false;
                }
                if (!inferMut && owner instanceof BabbleParser.FunctionLiteralContext && !declaredPrefix.explicitSharedThis()) {
                    reportOnce("Borrowed this escapes via " + destination + " : mark function as shared", at);
                }
            }
            for (int p = flat.params.nextSetBit(0); p >= 0; p = flat.params.nextSetBit(p + 1)) {
                markParamEscaped(p, destination, at);
            }
        }

        /** The name of a pseudo-parameter (a captured callback the owner forwards), or null. */
        private String pseudoForwardedName(int bit) {
            for (Map.Entry<ParserRuleContext, Integer> e : pseudoForwarded.entrySet()) {
                if (e.getValue() == bit && e.getKey() instanceof BabbleParser.FnParamContext param) {
                    return param.ID().getText();
                }
            }
            return null;
        }

        void markParamEscaped(int p, String destination, ParserRuleContext at) {
            if (p >= params.size()) {
                String name = pseudoForwardedName(p);
                if (name != null && !inferMut) {
                    reportOnce("Parameter in calling clause escapes via " + destination
                            + " : remove " + name + " from calling and mark it as shared", at);
                }
                return;
            }
            if (borrowParams[p]) {
                borrowParams[p] = false;
            }
            if (!inferMut && owner instanceof BabbleParser.FunctionLiteralContext) {
                ParamMods declared = parseParamMods(params.get(p), false);
                if (!declared.shared() && !declared.unique()) {
                    String pName = ScopeAnalysis.paramId(params.get(p)).getText();
                    if (inCallingClause((BabbleParser.FunctionLiteralContext) owner, pName)) {
                        // calling forwards f's effects only while the call runs: an escaping f
                        // must leave the clause and be shared, whatever calls it later.
                        reportOnce("Parameter in calling clause escapes via " + destination
                                + " : remove " + pName + " from calling and mark it as shared", at);
                        return;
                    }
                    reportOnce("Borrowed parameter " + pName + " escapes via " + destination
                            + " : mark parameter as shared " + pName, at);
                }
            }
        }

        void recordReturnOrigin(Origin retOrigin, ParserRuleContext at) {
            if (retOrigin == Origin.IMMUTABLE) {
                return;
            }
            Origin flat = retOrigin.hasElementTaint() ? retOrigin.flattenElements() : retOrigin;
            if (!retOrigin.unique || !flat.params.isEmpty() || flat.aliasesThis || flat.external) {
                uniqueResult = false;
            } else if (at instanceof BabbleParser.ReturnExpressionContext
                    && retOrigin.uniqueOwner instanceof ParserRuleContext decl) {
                env.put(decl, Origin.MOVED);
            }
            // A forwarded callback is only accounted for while the call runs : returning it (or a
            // value aliasing it, such as a closure capturing it) would let it run unaccounted (BIP017-D6).
            if (!inferMut) {
                for (int p = flat.params.nextSetBit(0); p >= 0; p = flat.params.nextSetBit(p + 1)) {
                    String name = p >= params.size() ? pseudoForwardedName(p)
                            : forwardedParams.contains(p) ? ScopeAnalysis.paramId(params.get(p)).getText() : null;
                    if (name != null) {
                        reportOnce("Parameter in calling clause escapes via return : remove " + name + " from calling", at);
                    }
                }
            }
            // BIP016-D10: returning a @BORROWED parameter or `this` does not store it; record return provenance for callers!
            BitSet returned = flat.params;
            if (returned.length() > params.size()) {
                returned = (BitSet) returned.clone();
                returned.clear(params.size(), returned.length());
            }
            returnParams.or(returned);
            if (flat.aliasesThis) {
                returnThis = true;
            }
            if (flat.external) {
                returnExternal = true;
            }
        }

        /**
         * The origin of a closure value (BIP017-D6) : capturing a parameter or {@code this} aliases
         * it, like returning it does (BIP016-D10), so the closure carries their provenance and
         * escapes only where the closure itself is stored. Captured locals are still stored on
         * capture, their later assignments being outer-variable stores.
         */
        private Origin checkClosureCaptureEscape(BabbleParser.FunctionLiteralContext inner) {
            boolean frameLocal = frameLocalOwner.get(inner) == owner;
            CallableSummary innerSum = summaries.get(inner);
            Resolution res = pass.analysis.resolution();
            if (res == null) {
                return Origin.IMMUTABLE;
            }
            Origin closure = Origin.IMMUTABLE;
            boolean capturesThis = innerContainsThis(inner);
            if (capturesThis) {
                if (!frameLocal) {
                    closure = Origin.ofThis(THIS_KEY, false).stripDirectIdentity(false);
                }
                if (innerSum != null && innerSum.effects().mutThis() && !mutThis) {
                    if (inferMut) {
                        mutThis = true;
                    } else {
                        reportOnce("Inner closure mutates this : enclosing function is not declared mut", inner);
                    }
                }
            }
            for (Resolution.Variable cap : res.captured(inner)) {
                ParserRuleContext decl = cap.declaration();
                if (decl == null) {
                    continue;
                }
                Origin orig = env.get(decl);
                if (orig == null && paramIndex.containsKey(decl)) {
                    orig = Origin.ofParam(paramIndex.get(decl), decl,
                            isDeeplyImmutable(checker.typeOfDeclarationForEffects(decl)));
                } else if (orig == null && pseudoForwarded.containsKey(decl)) {
                    orig = Origin.ofParam(pseudoForwarded.get(decl), decl, false);
                }
                if (orig == null || frameLocal) {
                    continue;
                }
                if (paramIndex.containsKey(decl) || pseudoForwarded.containsKey(decl)) {
                    Origin flat = orig.hasElementTaint() ? orig.flattenElements() : orig;
                    Origin alias = flat.stripDirectIdentity(false);
                    closure = closure == Origin.IMMUTABLE ? alias : closure.join(alias);
                } else {
                    markStoredOrigin(orig, "captured closure", inner);
                }
            }
            return closure;
        }

        private boolean innerContainsThis(ParseTree tree) {
            if (tree instanceof BabbleParser.ClassExpressionContext || tree instanceof BabbleParser.ObjectExpressionContext
                    || tree instanceof BabbleParser.InterfaceExpressionContext) {
                return false;
            }
            if (tree instanceof BabbleParser.ThisExpressionContext) {
                return true;
            }
            for (int i = 0; i < tree.getChildCount(); i++) {
                if (innerContainsThis(tree.getChild(i))) {
                    return true;
                }
            }
            return false;
        }

        private Origin evalIf(BabbleParser.IfExprContext ctx) {
            eval(ctx.test);
            Map<ParserRuleContext, Origin> snapshot = new IdentityHashMap<>(env);

            Origin thenOrig = eval(ctx.thenBlock);
            Map<ParserRuleContext, Origin> afterThen = env;

            env = new IdentityHashMap<>(snapshot);
            ParserRuleContext otherwise = ctx.elseBlock != null ? ctx.elseBlock : ctx.elseIf;
            Origin elseOrig = otherwise != null ? eval(otherwise) : Origin.IMMUTABLE;
            Map<ParserRuleContext, Origin> afterElse = env;

            env = mergeEnvs(afterThen, afterElse);
            return thenOrig.join(elseOrig);
        }

        private Origin evalWhile(BabbleParser.WhileExpressionContext ctx) {
            terminating = false;
            stmtHasSideEffect = true;
            Origin[] breakAcc = new Origin[1];
            loopBreakOrigins.push(breakAcc);
            Origin bodyOrig = Origin.IMMUTABLE;
            // Whether the loop itself can throw, for loops that never end (BIP024-D8).
            boolean outerNothrow = nothrow;
            nothrow = true;
            try {
                int iter = 0;
                while (iter++ < 8) {
                    Map<ParserRuleContext, Origin> before = new IdentityHashMap<>(env);
                    eval(ctx.test);
                    bodyOrig = eval(ctx.whileBlock);
                    Map<ParserRuleContext, Origin> merged = mergeEnvs(before, env);
                    if (merged.equals(before)) {
                        env = merged;
                        break;
                    }
                    env = merged;
                }
            } finally {
                loopBreakOrigins.pop();
                loopNothrow.put(ctx, nothrow);
                nothrow = outerNothrow && nothrow;
            }
            return breakAcc[0] != null ? bodyOrig.join(breakAcc[0]) : bodyOrig;
        }

        private Origin evalFor(BabbleParser.ForExpressionContext ctx) {
            stmtHasSideEffect = true;
            Origin iterOrig = eval(ctx.iterable);
            Type iterType = effectiveType(ctx.iterable);
            TypeChecker.ForIterableInfo info = checker.forIterableInfo(ctx);
            boolean trackConcurrentMut = false;
            if (info != null) {
                recordTargetRead(iterOrig, iterType);
                switch (info.kind()) {
                    case ARRAY -> {
                        // Indexed array loop : @HERMETIC @NOTHROW, terminates; mutable read recorded above.
                    }
                    case JAVA_ITERABLE -> {
                        terminating = false;
                        nothrow = false;
                        Type unwrapped = iterType instanceof Type.Frozen f ? f.of() : iterType;
                        if (unwrapped instanceof Type.JavaInstance ji) {
                            JavaCallProfile iterProf = resolveJavaMethodProfile(ji.type(), "iterator", lookupPublicMethod(ji.type(), "iterator"));
                            applyEffects(iterProf.effects());
                            if (iterProf.mutReceiver()) {
                                recordTargetMutation(iterOrig, ctx.iterable, "iterator", ctx.iterable, false);
                            }
                            if (iterProf.view()) {
                                trackConcurrentMut = true;
                            }
                        }
                    }
                    case JAVA_ITERATOR -> {
                        terminating = false;
                        nothrow = false;
                        recordTargetMutation(iterOrig, ctx.iterable, "iterator.next", ctx.iterable, true);
                    }
                    case BABBLE_TO_JAVA_ITERABLE -> {
                        terminating = false;
                        nothrow = false;
                        if (info.iteratorFn() != null) {
                            applyEffects(info.iteratorFn().effects());
                            if (info.iteratorFn().effects().mutThis()) {
                                recordTargetMutation(iterOrig, ctx.iterable, "iterator", ctx.iterable, false);
                            }
                        }
                    }
                    case BABBLE_ITERABLE -> {
                        terminating = false;
                        if (info.iteratorFn() != null) {
                            applyEffects(info.iteratorFn().effects());
                            if (info.iteratorFn().effects().mutThis()) {
                                recordTargetMutation(iterOrig, ctx.iterable, "iterator", ctx.iterable, false);
                            }
                        }
                        if (info.hasNextFn() != null) {
                            applyEffects(info.hasNextFn().effects());
                        }
                        if (info.nextFn() != null) {
                            applyEffects(info.nextFn().effects());
                        }
                    }
                    case BABBLE_ITERATOR -> {
                        terminating = false;
                        if (info.hasNextFn() != null) {
                            applyEffects(info.hasNextFn().effects());
                            if (info.hasNextFn().effects().mutThis()) {
                                recordTargetMutation(iterOrig, ctx.iterable, "hasNext", ctx.iterable, false);
                            }
                        }
                        if (info.nextFn() != null) {
                            applyEffects(info.nextFn().effects());
                            if (info.nextFn().effects().mutThis()) {
                                recordTargetMutation(iterOrig, ctx.iterable, "next", ctx.iterable, false);
                            }
                        }
                    }
                    case DYNAMIC_UNION -> {
                        terminating = false;
                        nothrow = false;
                    }
                }
            }
            Type loopVarType = checker.typeOfDeclarationForEffects(ctx);
            Origin elemOrig;
            if (loopVarType == null || isDeeplyImmutable(loopVarType)) {
                elemOrig = Origin.IMMUTABLE;
            } else {
                Origin flat = iterOrig.hasElementTaint() ? iterOrig.flattenElements() : iterOrig;
                elemOrig = new Origin(flat.params, flat.aliasesThis, flat.external, false, false, false, null,
                        Origin.EMPTY_BITS, false, false, Set.of(ctx), new PathKey(ctx, List.of()));
            }
            Origin[] breakAcc = new Origin[1];
            loopBreakOrigins.push(breakAcc);
            if (trackConcurrentMut && !iterOrig.directKeys.isEmpty()) {
                activeForIterables.add(iterOrig);
            }
            Origin bodyOrig = Origin.IMMUTABLE;
            try {
                int iter = 0;
                while (iter++ < 8) {
                    Map<ParserRuleContext, Origin> before = new IdentityHashMap<>(env);
                    env.put(ctx, elemOrig);
                    bodyOrig = eval(ctx.forBlock);
                    env.remove(ctx);
                    Map<ParserRuleContext, Origin> merged = mergeEnvs(before, env);
                    if (merged.equals(before)) {
                        env = merged;
                        break;
                    }
                    env = merged;
                }
            } finally {
                if (trackConcurrentMut && !iterOrig.directKeys.isEmpty()) {
                    activeForIterables.remove(activeForIterables.size() - 1);
                }
                loopBreakOrigins.pop();
            }
            return breakAcc[0] != null ? bodyOrig.join(breakAcc[0]) : bodyOrig;
        }

        private Origin evalBooleanOp(BabbleParser.BooleanOpContext ctx) {
            eval(ctx.left);
            Map<ParserRuleContext, Origin> before = new IdentityHashMap<>(env);
            eval(ctx.right);
            env = mergeEnvs(before, env);
            return Origin.IMMUTABLE;
        }

        private Origin evalBinaryOp(BabbleParser.BinaryOpContext bin) {
            Origin leftOrig = eval(bin.left);
            Origin rightOrig = eval(bin.right);
            String op = bin.op.getText();
            Type lt = effectiveType(bin.left);
            Type rt = effectiveType(bin.right);
            Type res = checker.rawTypeOf(bin);
            if (res == Type.ERROR || lt == Type.ERROR || rt == Type.ERROR || hasPriorDiagnostic(bin)) {
                return Origin.IMMUTABLE;
            }
            switch (op) {
                case "+" -> {
                    if (res == Type.STRING && (lt == Type.STRING || rt == Type.STRING)) {
                        if (lt != Type.STRING) {
                            applyEffects(toStringProfile(lt));
                            if (lt instanceof Type.Array) {
                                recordTargetRead(leftOrig, lt);
                            }
                        }
                        if (rt != Type.STRING) {
                            applyEffects(toStringProfile(rt));
                            if (rt instanceof Type.Array) {
                                recordTargetRead(rightOrig, rt);
                            }
                        }
                    }
                }
                case "==", "!=", "<>" -> {
                    applyEffects(equalityProfile(lt, rt));
                    if (lt instanceof Type.Array) {
                        recordTargetRead(leftOrig, lt);
                    }
                    if (rt instanceof Type.Array) {
                        recordTargetRead(rightOrig, rt);
                    }
                }
                case "<", "<=", ">", ">=" -> applyEffects(comparisonProfile(lt, rt));
                default -> {
                    // -, *, / on numbers are pure deterministic nothrow (1 / 0 == Infinity).
                }
            }
            return Origin.IMMUTABLE;
        }

        private Map<ParserRuleContext, Origin> mergeEnvs(Map<ParserRuleContext, Origin> a, Map<ParserRuleContext, Origin> b) {
            Map<ParserRuleContext, Origin> out = new IdentityHashMap<>();
            Set<ParserRuleContext> keys = Collections.newSetFromMap(new IdentityHashMap<>());
            keys.addAll(a.keySet());
            keys.addAll(b.keySet());
            for (ParserRuleContext k : keys) {
                Origin oa = a.get(k);
                Origin ob = b.get(k);
                if (oa == null) {
                    out.put(k, ob);
                } else if (ob == null) {
                    out.put(k, oa);
                } else {
                    out.put(k, oa.join(ob));
                }
            }
            return out;
        }

        /** The type of an expression where it occurs, narrowed by the tests that guard it (as the typing walk recorded it). */
        private Type effectiveType(BabbleParser.ExpressionContext expr) {
            BabbleParser.ExpressionContext unwrapped = unwrapParens(expr);
            return checker.rawTypeOf(unwrapped instanceof BabbleParser.SelectorContext sel && sel.expression() == null ? unwrapped : expr);
        }
        private boolean isRedundantCast(Type src, Type dst) {
            if (src == null || dst == null || src == Type.ERROR || dst == Type.ERROR) {
                return true;
            }
            return checker.assignableNoWidening(src, dst);
        }

        private void applyEffects(EffectSet callee) {
            if (!callee.isHermetic()) {
                hermetic = false;
            }
            if (!callee.isDeterministic()) {
                deterministic = false;
            }
            if (!callee.isNothrow()) {
                nothrow = false;
            }
            if (!callee.terminating()) {
                terminating = false;
            }
        }

        private void applyCallableSummaryEffects(CallableSummary summary) {
            applyEffects(summary.effects());
            for (BabbleParser.FunctionLiteralContext readOwner : summary.frameReadOwners()) {
                if (readOwner != owner) {
                    if (frameChain.contains(readOwner)) {
                        frameReadOwners.add(readOwner);
                    } else {
                        deterministic = false;
                    }
                }
            }
            for (BabbleParser.FunctionLiteralContext writeOwner : summary.frameWriteOwners()) {
                stmtHasSideEffect = true;
                if (writeOwner != owner) {
                    if (frameChain.contains(writeOwner)) {
                        frameWriteOwners.add(writeOwner);
                    } else {
                        hermetic = false;
                        deterministic = false;
                    }
                }
            }
        }

        private BabbleParser.FunctionLiteralContext enclosingFunctionOfDecl(ParserRuleContext decl) {
            ScopeNode s = pass.definitionScopes.get(decl);
            while (s != null) {
                if (s.kind == ScopeNode.Kind.FUNCTION && s.owner instanceof BabbleParser.FunctionLiteralContext fn) {
                    return fn;
                }
                if (s.kind == ScopeNode.Kind.CLASS_BODY || s.kind == ScopeNode.Kind.STATIC_BODY
                        || s.kind == ScopeNode.Kind.INTERFACE_BODY) {
                    return null;
                }
                s = s.parent;
            }
            return null;
        }
    }

    // ================================================================ 12. Bounded Call-Site Specialization (§4.5)

    private CallableSummary trySpecializeCall(BabbleParser.FunctionLiteralContext targetLit,
                                              List<Type.Parameter> calleeParams,
                                              List<BabbleParser.CallParameterContext> args,
                                              BabbleParser.ExpressionContext[] argExprs,
                                              EffectChecker callerEC) {
        if (activeSpecializations.size() >= MAX_SPECIALIZATION_DEPTH) {
            return null;
        }
        List<BabbleParser.FnParamContext> paramDecls = targetLit.fnParameters().fnParam();
        Map<ParserRuleContext, Type.Function> boundCallbacks = new IdentityHashMap<>();
        List<Object> keyItems = new ArrayList<>(paramDecls.size());
        boolean anyCallback = false;
        for (int i = 0; i < args.size(); i++) {
            int pIdx = checker.parameterIndexForArg(calleeParams, args, args.get(i));
            if (pIdx < 0 || pIdx >= paramDecls.size()) {
                continue;
            }
            Type argType = callerEC.checker.rawTypeOf(argExprs[i]);
            if (argType instanceof Type.Function argFn) {
                BabbleParser.FunctionLiteralContext argLit = callerEC.checker.literalOf(argFn);
                if (argLit != null) {
                    CallableSummary argSum = callerEC.summaries.get(argLit);
                    if (argSum != null) {
                        argFn = enrichFunctionType(argFn, argSum);
                    }
                }
                boundCallbacks.put(paramDecls.get(pIdx), argFn);
                keyItems.add(argLit != null ? argLit : argFn);
                anyCallback = true;
            } else {
                keyItems.add(null);
            }
        }
        if (!anyCallback) {
            return null;
        }
        SpecKey key = new SpecKey(targetLit, keyItems);
        if (activeSpecializations.contains(key)) {
            return null;
        }
        CallableSummary cached = specializationCache.get(key);
        if (cached != null) {
            return cached;
        }
        activeSpecializations.push(key);
        try {
            CallableSummary base = summaries.getOrDefault(targetLit, initialSummary(targetLit));
            ParsedPrefix targetPrefix = parseFunctionPrefix(targetLit, false);
            // Start optimistic for effects when specializing, respecting declared mut/shared slot contracts.
            int n = paramDecls.size();
            boolean[] mp = base.mutParams().clone();
            boolean[] bp = new boolean[n];
            for (int i = 0; i < n; i++) {
                ParamMods pm = parseParamMods(paramDecls.get(i), false);
                bp[i] = !pm.shared() && !pm.unique();
            }
            EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
            boolean borrowThis = !targetPrefix.explicitSharedThis();
            EffectSet startSet = new EffectSet(opt, EnumSet.noneOf(Effect.class),
                    base.effects().mutThis(), borrowThis, targetPrefix.explicitSharedThis(),
                    true, targetPrefix.uniqueReturn(), true, false, false, false);
            CallableSummary start = new CallableSummary(startSet, mp, bp, base.uniqueParams());
            CallableSummary specialized = finalizeSummary(targetLit, recomputeSummary(targetLit, start, boundCallbacks, false));
            specializationCache.put(key, specialized);
            return specialized;
        } finally {
            activeSpecializations.pop();
        }
    }

    private static Type.Function enrichFunctionType(Type.Function argFn, CallableSummary argSum) {
        List<Type.Parameter> updatedParams = new ArrayList<>(argFn.parameters().size());
        for (int k = 0; k < argFn.parameters().size(); k++) {
            Type.Parameter p = argFn.parameters().get(k);
            boolean m = k < argSum.mutParams().length && argSum.mutParams()[k];
            boolean b = k < argSum.borrowParams().length && argSum.borrowParams()[k];
            updatedParams.add(p.withModifiers(m, b));
        }
        return argFn.withEffects(argSum.effects(), updatedParams);
    }

    TypedModuleSummary.CallableEffectTemplate buildCallableTemplate(BabbleParser.FunctionLiteralContext lit, Type.Function fn) {
        CallableSummary base = visibleSummaries.getOrDefault(lit, summaries.get(lit));
        if (base == null || fn == null) {
            return null;
        }
        ParsedPrefix prefix = parseFunctionPrefix(lit, false);
        List<BabbleParser.FnParamContext> paramDecls = lit.fnParameters().fnParam();
        int n = paramDecls.size();
        boolean lockedNoSpec = prefix.axiom() || prefix.explicitVolatile();
        if (lockedNoSpec || n == 0) {
            return new TypedModuleSummary.CallableEffectTemplate(
                    base, true, base.effects(), base.mutParams(), base.borrowParams(),
                    new boolean[0], new EffectSet[0], new long[0][]);
        }
        boolean anyFnParam = false;
        for (Type.Parameter p : fn.parameters()) {
            if (p.type() instanceof Type.Function) {
                anyFnParam = true;
                break;
            }
        }
        if (!anyFnParam) {
            return new TypedModuleSummary.CallableEffectTemplate(
                    base, prefix.io(), base.effects(), base.mutParams(), base.borrowParams(),
                    new boolean[0], new EffectSet[0], new long[0][]);
        }

        EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
        EffectSet pureEff = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true);
        boolean[] startBorrow = new boolean[n];
        for (int i = 0; i < n; i++) {
            ParamMods pm = parseParamMods(paramDecls.get(i), false);
            startBorrow[i] = !pm.shared() && !pm.unique();
        }
        boolean startBorrowThis = !prefix.explicitSharedThis();
        EffectSet startSet = new EffectSet(opt, EnumSet.noneOf(Effect.class),
                base.effects().mutThis(), startBorrowThis, prefix.explicitSharedThis(),
                true, prefix.uniqueReturn(), true, false, false, false);
        CallableSummary start = new CallableSummary(startSet, base.mutParams().clone(), startBorrow, base.uniqueParams());

        Map<ParserRuleContext, Type.Function> allPure = new IdentityHashMap<>();
        EffectSet[] defaultCallbackEffects = new EffectSet[n];
        for (int i = 0; i < n && i < fn.parameters().size(); i++) {
            if (fn.parameters().get(i).type() instanceof Type.Function cbFn) {
                defaultCallbackEffects[i] = cbFn.effects();
                allPure.put(paramDecls.get(i), cbFn.withEffects(pureEff, allBorrowParams(cbFn.parameters())));
            }
        }
        CallableSummary residual = finalizeSummary(lit, recomputeSummary(lit, start, allPure, false));

        boolean[] forwarded = new boolean[n];
        long[][] paramMutFromCbArg = new long[n][];
        for (int i = 0; i < n && i < fn.parameters().size(); i++) {
            if (!(fn.parameters().get(i).type() instanceof Type.Function cbFn)) {
                continue;
            }
            Map<ParserRuleContext, Type.Function> probe = new IdentityHashMap<>(allPure);
            probe.put(paramDecls.get(i), cbFn.withEffects(EffectSet.EMPTY, cbFn.parameters()));
            CallableSummary probed = finalizeSummary(lit, recomputeSummary(lit, start, probe, false));
            if (!probed.effects().effects().equals(residual.effects().effects())) {
                forwarded[i] = true;
            }
            int cbArity = cbFn.parameters().size();
            paramMutFromCbArg[i] = new long[cbArity];
            for (int m = 0; m < cbArity; m++) {
                List<Type.Parameter> mutCbParams = new ArrayList<>(cbArity);
                for (int k = 0; k < cbArity; k++) {
                    Type.Parameter cp = cbFn.parameters().get(k);
                    mutCbParams.add(cp.withModifiers(k == m, k != m));
                }
                Map<ParserRuleContext, Type.Function> mutProbe = new IdentityHashMap<>(allPure);
                mutProbe.put(paramDecls.get(i), cbFn.withEffects(pureEff, mutCbParams));
                CallableSummary mutProbed = finalizeSummary(lit, recomputeSummary(lit, start, mutProbe, false));
                long mask = 0L;
                for (int j = 0; j < n && j < 64; j++) {
                    if (mutProbed.mutParams()[j] && !residual.mutParams()[j]) {
                        mask |= (1L << j);
                        forwarded[i] = true;
                    }
                }
                paramMutFromCbArg[i][m] = mask;
            }
        }
        return new TypedModuleSummary.CallableEffectTemplate(
                base, false, residual.effects(), residual.mutParams(), residual.borrowParams(),
                forwarded, defaultCallbackEffects, paramMutFromCbArg);
    }

    private static boolean[] allTrue(int n) {
        boolean[] b = new boolean[n];
        for (int i = 0; i < n; i++) {
            b[i] = true;
        }
        return b;
    }

    private static List<Type.Parameter> allBorrowParams(List<Type.Parameter> params) {
        List<Type.Parameter> out = new ArrayList<>(params.size());
        for (Type.Parameter p : params) {
            out.add(p.withModifiers(false, true));
        }
        return out;
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
