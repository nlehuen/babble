package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.effects.Associative;
import org.babblelang.effects.Borrow;
import org.babblelang.effects.Commutative;
import org.babblelang.effects.Deterministic;
import org.babblelang.effects.Effect;
import org.babblelang.effects.Hermetic;
import org.babblelang.effects.Idempotent;
import org.babblelang.effects.Impure;
import org.babblelang.effects.Injective;
import org.babblelang.effects.Monotone;
import org.babblelang.effects.Mut;
import org.babblelang.effects.Nothrow;
import org.babblelang.effects.Pure;
import org.babblelang.effects.Unique;
import org.babblelang.engine.impl.Interpreter;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compile-time effect, parameter/receiver mutability, borrow-escape, static {@code mut}
 * exclusivity, flow-sensitive {@code nothrow}, and compositional algebraic checker (BIP007-P1).
 */
final class EffectChecker {

    private static final int MAX_SPECIALIZATION_DEPTH = 4;

    record ParsedPrefix(EnumSet<Effect> effects,
                        boolean mutThis,
                        boolean borrowThis,
                        boolean uniqueReturn,
                        boolean axiom,
                        boolean impure,
                        boolean hasAnyModifier) {
        static final ParsedPrefix EMPTY = new ParsedPrefix(
                EnumSet.noneOf(Effect.class), false, false, false, false, false, false);

        ParsedPrefix(EnumSet<Effect> effects, boolean mutThis, boolean borrowThis,
                     boolean axiom, boolean impure, boolean hasAnyModifier) {
            this(effects, mutThis, borrowThis, false, axiom, impure, hasAnyModifier);
        }

        EffectSet toEffectSet() {
            if (impure) {
                return EffectSet.IMPURE_DECLARED;
            }
            return EffectSet.ofDeclared(effects, mutThis, borrowThis, uniqueReturn, axiom);
        }
    }

    record ParamMods(boolean mut, boolean borrow, boolean unique) {
        static final ParamMods NONE = new ParamMods(false, false, false);

        ParamMods(boolean mut, boolean borrow) {
            this(mut, borrow, false);
        }
    }

    record CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams, boolean[] uniqueParams,
                           Set<BabbleParser.FunctionLiteralContext> frameReadOwners,
                           Set<BabbleParser.FunctionLiteralContext> frameWriteOwners) {
        CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams,
                        Set<BabbleParser.FunctionLiteralContext> frameReadOwners,
                        Set<BabbleParser.FunctionLiteralContext> frameWriteOwners) {
            this(effects, mutParams, borrowParams, new boolean[mutParams.length], frameReadOwners, frameWriteOwners);
        }

        CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams, boolean[] uniqueParams) {
            this(effects, mutParams, borrowParams, uniqueParams, Set.of(), Set.of());
        }

        CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams) {
            this(effects, mutParams, borrowParams, new boolean[mutParams.length], Set.of(), Set.of());
        }
    }

    record JavaCallProfile(EffectSet effects, boolean mutReceiver, boolean borrowReceiver,
                           boolean[] mutParams, boolean[] borrowParams, boolean[] uniqueParams,
                           boolean explicitNothrow) {
        JavaCallProfile(EffectSet effects, boolean mutReceiver, boolean borrowReceiver,
                        boolean[] mutParams, boolean[] borrowParams, boolean explicitNothrow) {
            this(effects, mutReceiver, borrowReceiver, mutParams, borrowParams, new boolean[mutParams.length], explicitNothrow);
        }
    }

    /** Tracks alias provenance and direct binding identity for static mut/borrow/exclusivity checks. */
    private static final class Origin {
        static final Origin IMMUTABLE = new Origin(new BitSet(0), false, false, false, false, null, Set.of(), null);
        static final Origin MOVED = new Origin(new BitSet(0), false, false, false, true, null, Set.of(), null);

        final BitSet params;
        final boolean aliasesThis;
        final boolean external;
        final boolean unique;
        final boolean moved;
        /** Owning local/param declaration when {@code unique} is bound to a live variable, or {@code null} for a fresh rvalue. */
        final Object uniqueOwner;
        /** Direct local/param/this identity keys when an expression is a direct alias (e.g. {@code def y = x}). */
        final Set<Object> directKeys;
        /** Pure member path key when an expression is {@code base.f1.f2} without calls. */
        final PathKey pathKey;

        Origin(BitSet params, boolean aliasesThis, boolean external, boolean unique, boolean moved,
               Object uniqueOwner, Set<Object> directKeys, PathKey pathKey) {
            this.params = params;
            this.aliasesThis = aliasesThis;
            this.external = external;
            this.unique = unique;
            this.moved = moved;
            this.uniqueOwner = uniqueOwner;
            this.directKeys = directKeys;
            this.pathKey = pathKey;
        }

        Origin(BitSet params, boolean aliasesThis, boolean external, Set<Object> directKeys, PathKey pathKey) {
            this(params, aliasesThis, external, false, false, null, directKeys, pathKey);
        }

        static Origin ofParam(int index, Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            BitSet bits = new BitSet();
            bits.set(index);
            return new Origin(bits, false, false, false, false, null, Set.of(key), new PathKey(key, List.of()));
        }

        static Origin ofUniqueParam(int index, Object key, boolean immutable, boolean borrowed) {
            if (immutable) {
                return IMMUTABLE;
            }
            BitSet bits = new BitSet();
            if (borrowed) {
                bits.set(index);
            }
            return new Origin(bits, false, false, true, false, key, Set.of(key), new PathKey(key, List.of()));
        }

        static Origin ofUnique(Object key, Object uniqueOwner, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            Set<Object> keys = key != null ? Set.of(key) : Set.of();
            PathKey pk = key != null ? new PathKey(key, List.of()) : null;
            return new Origin(new BitSet(0), false, false, true, false, uniqueOwner, keys, pk);
        }

        static Origin ofThis(Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            return new Origin(new BitSet(0), true, false, false, false, null, Set.of(key), new PathKey(key, List.of()));
        }

        static Origin ofExternal(Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            Set<Object> keys = key != null ? Set.of(key) : Set.of();
            PathKey pk = key != null ? new PathKey(key, List.of()) : null;
            return new Origin(new BitSet(0), false, true, false, false, null, keys, pk);
        }

        boolean isEmpty() {
            return params.isEmpty() && !aliasesThis && !external && !unique && !moved;
        }

        Origin deriveMember(String field, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            PathKey nextPath = pathKey != null ? pathKey.append(field) : null;
            return new Origin((BitSet) params.clone(), aliasesThis, external, unique, false, uniqueOwner, Set.of(), nextPath);
        }

        Origin stripDirectIdentity(boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            return new Origin((BitSet) params.clone(), aliasesThis, external, unique, false, uniqueOwner, Set.of(), null);
        }

        Origin demoteToExternal(Origin taint) {
            BitSet merged = (BitSet) params.clone();
            boolean mergedThis = aliasesThis;
            if (taint != null && taint != IMMUTABLE) {
                merged.or(taint.params);
                mergedThis |= taint.aliasesThis;
            }
            return new Origin(merged, mergedThis, true, false, false, null, directKeys, pathKey);
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
            Object mergedOwner = mergedUnique && Objects.equals(this.uniqueOwner, other.uniqueOwner) ? this.uniqueOwner : null;
            boolean mergedExternal = this.external || other.external
                    || (!mergedUnique && (this.unique || other.unique) && !mergedThis && merged.isEmpty());
            return new Origin(merged, mergedThis, mergedExternal, mergedUnique, false, mergedOwner, keys, pk);
        }

        private Origin withoutExactSingleKey() {
            return new Origin((BitSet) params.clone(), aliasesThis, external, unique, moved, uniqueOwner, directKeys, pathKey);
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
                    && Objects.equals(uniqueOwner, that.uniqueOwner)
                    && params.equals(that.params)
                    && directKeys.equals(that.directKeys)
                    && Objects.equals(pathKey, that.pathKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(params, aliasesThis, external, unique, moved, uniqueOwner, directKeys, pathKey);
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
    /** Defs that carry {@code = axiom ...} or {@code = impure ...} on non-function-literal values. */
    private final Map<BabbleParser.DefExpressionContext, ParsedPrefix> defPrefixes = new IdentityHashMap<>();

    private final Map<BabbleParser.FunctionLiteralContext, ParsedPrefix> fnPrefixes = new IdentityHashMap<>();
    private final Map<ParserRuleContext, ParamMods> paramModsMap = new IdentityHashMap<>();
    private final Set<ParseTree> validatedTypeTrees = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Closures proven not to escape their enclosing function {@code F} (only called or passed to borrow slots). */
    private final Map<BabbleParser.FunctionLiteralContext, BabbleParser.FunctionLiteralContext> frameLocalOwner = new IdentityHashMap<>();
    /** Local defs of {@code F} captured by an *escaping* closure (cannot be treated as private frame state). */
    private final Set<ParserRuleContext> escapedCapturedLocals = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Converged internal summaries for all callables (FunctionLiteralContext and ClassExpressionContext). */
    private final Map<ParserRuleContext, CallableSummary> summaries = new IdentityHashMap<>();
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
        }
        if (pass.session != null) {
            localJavaEffects.putAll(pass.session.javaEffects);
            visibleSummaries.putAll(pass.session.callableSummaries);
        }
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

    ParsedPrefix parseFunctionPrefix(BabbleParser.FunctionLiteralContext ctx, boolean reportErrors) {
        if (!reportErrors) {
            ParsedPrefix cached = fnPrefixes.get(ctx);
            if (cached != null) {
                return cached;
            }
        }
        ParsedPrefix parsed = parseWords(
                ctx.effectPrefix() != null ? effectPrefixTokens(ctx.effectPrefix()) : List.of(),
                false, canReferenceThis(checker.scopeOf(ctx)), ctx, reportErrors);
        boolean uniqueReturn = ctx.uniqueReturn != null || parsed.uniqueReturn();
        // Check pure + mut parameter conflict.
        boolean hasMutParam = false;
        boolean hasAnyParamMod = false;
        for (BabbleParser.FnParamContext param : ctx.fnParameters().fnParam()) {
            ParamMods pm = parseParamMods(param, reportErrors);
            hasMutParam |= pm.mut();
            hasAnyParamMod |= pm.mut() || pm.borrow() || pm.unique();
        }
        if (reportErrors) {
            if (parsed.effects().contains(Effect.PURE) && hasMutParam) {
                checker.effectDiagnostic("pure and mut are mutually exclusive : use hermetic with mut parameters", ctx);
            }
            if (parsed.axiom() && parsed.effects().isEmpty() && !parsed.mutThis() && !parsed.borrowThis()
                    && !uniqueReturn && !hasAnyParamMod) {
                checker.effectDiagnostic("axiom requires at least one effect modifier", ctx);
            }
            if (parsed.axiom() && !pass.hostEffects.allowAxioms()) {
                checker.effectDiagnostic("axiom declarations are disabled on this engine", ctx);
            }
            if (parsed.impure() && !pass.hostEffects.allowAxioms()) {
                checker.effectDiagnostic("impure declarations are disabled on this engine", ctx);
            }
        }
        if (parsed.effects().contains(Effect.PURE) && hasMutParam) {
            EnumSet<Effect> fixed = EffectSet.copySet(parsed.effects());
            fixed.remove(Effect.PURE);
            fixed.add(Effect.HERMETIC);
            parsed = new ParsedPrefix(fixed, parsed.mutThis(), parsed.borrowThis(), uniqueReturn,
                    parsed.axiom(), parsed.impure(), true);
        } else if (uniqueReturn != parsed.uniqueReturn()) {
            parsed = new ParsedPrefix(parsed.effects(), parsed.mutThis(), parsed.borrowThis(), uniqueReturn,
                    parsed.axiom(), parsed.impure(), true);
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
        boolean report = reportErrors && validatedTypeTrees.add(ctx);
        List<Token> words = ctx.effectPrefix() != null ? effectPrefixTokens(ctx.effectPrefix()) : List.of();
        ParsedPrefix parsed = parseWords(words, true, true, ctx, report);
        boolean uniqueReturn = ctx.uniqueReturn != null || parsed.uniqueReturn();
        boolean hasMutParam = false;
        for (BabbleParser.FnTypeParamContext param : ctx.fnTypeParameters().fnTypeParam()) {
            ParamMods pm = parseParamMods(param, report);
            hasMutParam |= pm.mut();
        }
        if (report && parsed.effects().contains(Effect.PURE) && hasMutParam) {
            checker.effectDiagnostic("pure and mut are mutually exclusive : use hermetic with mut parameters", ctx);
        }
        if (parsed.effects().contains(Effect.PURE) && hasMutParam) {
            EnumSet<Effect> fixed = EffectSet.copySet(parsed.effects());
            fixed.remove(Effect.PURE);
            fixed.add(Effect.HERMETIC);
            return new ParsedPrefix(fixed, parsed.mutThis(), parsed.borrowThis(), uniqueReturn, false, false, true);
        }
        if (uniqueReturn != parsed.uniqueReturn()) {
            return new ParsedPrefix(parsed.effects(), parsed.mutThis(), parsed.borrowThis(), uniqueReturn, false, false, true);
        }
        return parsed;
    }

    ParamMods parseParamMods(ParserRuleContext param, boolean reportErrors) {
        if (!reportErrors) {
            ParamMods cached = paramModsMap.get(param);
            if (cached != null) {
                return cached;
            }
        }
        boolean mut = false;
        boolean borrow = false;
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
                case "borrow" -> {
                    if (borrow && reportErrors) {
                        checker.effectDiagnostic("Duplicate parameter modifier : borrow", modCtx);
                    }
                    borrow = true;
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
        ParamMods mods = (mut || borrow || unique) ? new ParamMods(mut, borrow, unique) : ParamMods.NONE;
        if (checker.inFile(param)) {
            paramModsMap.put(param, mods);
        }
        return mods;
    }

    private ParsedPrefix parseWords(List<Token> tokens, boolean inFunctionType, boolean hasThis,
                                    ParserRuleContext owner, boolean reportErrors) {
        if (tokens.isEmpty()) {
            return ParsedPrefix.EMPTY;
        }
        EnumSet<Effect> effects = EnumSet.noneOf(Effect.class);
        boolean mutThis = false;
        boolean borrowThis = false;
        boolean uniqueReturn = false;
        boolean axiom = false;
        boolean impure = false;
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
                case "impure" -> {
                    if (inFunctionType && reportErrors) {
                        checker.effectDiagnostic("impure is not allowed in a function type", owner);
                    }
                    impure = true;
                }
                case "pure" -> effects.add(Effect.PURE);
                case "hermetic" -> effects.add(Effect.HERMETIC);
                case "deterministic" -> effects.add(Effect.DETERMINISTIC);
                case "nothrow" -> effects.add(Effect.NOTHROW);
                case "idempotent" -> effects.add(Effect.IDEMPOTENT);
                case "commutative" -> effects.add(Effect.COMMUTATIVE);
                case "associative" -> effects.add(Effect.ASSOCIATIVE);
                case "monotone" -> effects.add(Effect.MONOTONE);
                case "injective" -> effects.add(Effect.INJECTIVE);
                case "unique" -> uniqueReturn = true;
                case "mut" -> {
                    if (!hasThis && reportErrors) {
                        checker.effectDiagnostic("mut on a function requires a receiver (this) : mark mutated parameters with mut", owner);
                    }
                    mutThis = true;
                }
                case "borrow" -> {
                    if (!hasThis && reportErrors) {
                        checker.effectDiagnostic("borrow on a function requires a receiver (this) : mark borrowed parameters with borrow", owner);
                    }
                    borrowThis = true;
                }
                default -> {
                    if (reportErrors) {
                        checker.effectDiagnostic("Unknown effect modifier : " + word, owner);
                    }
                }
            }
        }
        if (reportErrors) {
            if (axiom && impure) {
                checker.effectDiagnostic("axiom and impure cannot be combined", owner);
            } else if (impure && (!effects.isEmpty() || mutThis || borrowThis || uniqueReturn)) {
                checker.effectDiagnostic("impure cannot be combined with effect modifiers", owner);
            }
            if (effects.contains(Effect.PURE) && mutThis) {
                checker.effectDiagnostic("pure and mut are mutually exclusive : use hermetic mut", owner);
            }
        }
        if (effects.contains(Effect.PURE) && mutThis) {
            effects.remove(Effect.PURE);
            effects.add(Effect.HERMETIC);
        } else if (effects.contains(Effect.PURE)) {
            effects.add(Effect.HERMETIC);
        }
        return new ParsedPrefix(effects, mutThis, borrowThis, uniqueReturn, axiom, impure, true);
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
        if (from == Type.PRINT) {
            effects = EffectSet.EMPTY;
            mutParams = new boolean[0];
            borrowParams = new boolean[0];
            borrowRest = false;
        } else if (from instanceof Type.JavaMethod jm) {
            Method exact = JavaTypeBridge.uniqueArityMethod(jm.owner(), jm.name(), target.parameters().size());
            JavaCallProfile profile = resolveJavaMethodProfile(jm.owner(), jm.name(), exact);
            effects = profile.effects();
            mutParams = profile.mutParams();
            borrowParams = profile.borrowParams();
            borrowRest = profile.borrowReceiver();
            if (!target.effects().hasExplicitAnnotations() && effects.mutThis()) {
                effects = EffectSet.ofInferred(effects.effects(), false, effects.borrowThis());
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
            if (mut && !tp.mut() || tp.borrow() && !borrow) {
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

    // ================================================================ 3. Pre-pass : Axiom & Impure Collection

    private void collectDeclarations(BabbleParser.FileContext file) {
        // 1. Standalone effectDeclarations recorded by BlockScopes.
        for (BlockScopes.PendingEffectDecl pending : pass.effectDeclarations) {
            BabbleParser.EffectDeclarationContext ctx = pending.ctx();
            ScopeNode scope = pending.scope();
            List<Token> words = effectClauseTokens(ctx.effectClause());
            boolean isCtor = ctx.ctorOpen != null;
            ParsedPrefix prefix = parseTargetPrefix(words, isCtor, ctx);
            if (!pass.hostEffects.allowAxioms()) {
                checker.effectDiagnostic((prefix.impure() ? "impure" : "axiom") + " declarations are disabled on this engine", ctx);
                continue;
            }
            checkDeclarationPlacement(scope, prefix.impure() ? "impure" : "axiom", ctx);
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
                checker.effectDiagnostic((prefix.impure() ? "impure" : "axiom") + " declarations are disabled on this engine", def);
            } else {
                if (scope != null) {
                    checkDeclarationPlacement(scope, prefix.impure() ? "impure" : "axiom", def);
                }
                Type valType = def.value != null ? checker.rawTypeOf(def.value) : null;
                HostEffects.JavaTarget target = null;
                if (valType instanceof Type.JavaMethod jm) {
                    target = HostEffects.JavaTarget.ofMethod(jm.owner(), jm.name());
                } else if (valType instanceof Type.JavaClassValue jcv) {
                    target = HostEffects.JavaTarget.ofClass(jcv.type());
                } else if (valType != null && !(valType instanceof Type.Function) && valType != Type.ERROR) {
                    checker.effectDiagnostic("Invalid " + (prefix.impure() ? "impure" : "axiom")
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
        ParsedPrefix parsed = parseWords(words, false, !isCtor, ctx, true);
        if (parsed.axiom() && parsed.effects().isEmpty() && !parsed.mutThis() && !parsed.borrowThis()) {
            checker.effectDiagnostic("axiom requires at least one effect modifier", ctx);
        }
        return parsed;
    }

    private void checkDeclarationPlacement(ScopeNode scope, String keyword, ParserRuleContext ctx) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            if (s.kind == ScopeNode.Kind.FUNCTION && s.owner instanceof BabbleParser.FunctionLiteralContext fn) {
                ParsedPrefix p = parseFunctionPrefix(fn, false);
                if (!p.axiom() && (p.effects().contains(Effect.PURE)
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
            String pkg = Interpreter.unescape(targetCtx.STRING().getText());
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
        if (prefix.impure()) {
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
        if (prefix.impure()) {
            return HostEffects.OverrideEntry.impureEntry();
        }
        return new HostEffects.OverrideEntry(prefix.effects(), prefix.mutThis(), prefix.borrowThis(), prefix.uniqueReturn(), false);
    }

    // ================================================================ 4. 7-Tier Java Effect Resolution

    JavaCallProfile resolveJavaMethodProfile(Class<?> owner, String methodName, Method exactMethod) {
        // Tier 1: File / module / session method override.
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
        // Tier 6: Standard value protocol defaults (equals, compareTo, hashCode, toString) + String methods.
        JavaCallProfile protocol = standardProtocolDefault(owner, methodName, exactMethod);
        if (protocol != null) {
            return protocol;
        }
        // Tier 6.5 (opt-in BIP007-P3): ASM bytecode static analysis of closed Java helpers.
        if (exactMethod != null && pass.hostEffects.bytecodeEffectAnalysis()) {
            BytecodeEffectAnalyzer.Result analyzed = BytecodeEffectAnalyzer.analyze(exactMethod);
            if (!analyzed.effects().isUnconstrained()) {
                int pCount = exactMethod.getParameterCount();
                boolean[] borrowParams = new boolean[pCount];
                if (analyzed.effects().isPure()) {
                    Arrays.fill(borrowParams, true);
                }
                boolean isStatic = Modifier.isStatic(exactMethod.getModifiers());
                return new JavaCallProfile(analyzed.effects(), false, !isStatic && analyzed.effects().isPure(),
                        new boolean[pCount], borrowParams, analyzed.effects().isNothrow());
            }
        }
        // Tier 7: Unconstrained default.
        int paramCount = exactMethod != null ? exactMethod.getParameterCount() : 0;
        return new JavaCallProfile(EffectSet.EMPTY, false, false, new boolean[paramCount], new boolean[paramCount], false);
    }

    JavaCallProfile resolveJavaConstructorProfile(Class<?> owner, Constructor<?> exactCtor) {
        // Tier 1: File / module / session constructor (Class()) override.
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
        // Tier 6: Built-in BIP011 core type constructor profiles.
        int paramCount = exactCtor != null ? exactCtor.getParameterCount() : 0;
        if (owner == String.class) {
            boolean[] borrowParams = new boolean[paramCount];
            Arrays.fill(borrowParams, true);
            EffectSet set = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, false);
            return new JavaCallProfile(set, false, false, new boolean[paramCount], borrowParams, false);
        }
        if (isCoreCollectionClass(owner)
                || owner == java.util.AbstractMap.SimpleImmutableEntry.class
                || owner == java.util.AbstractMap.SimpleEntry.class) {
            EffectSet set = EffectSet.ofInferred(EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC), false, false);
            return new JavaCallProfile(set, false, false, new boolean[paramCount], new boolean[paramCount], false);
        }
        // Tier 7: Unconstrained default.
        return new JavaCallProfile(EffectSet.EMPTY, false, false, new boolean[paramCount], new boolean[paramCount], false);
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
        if (entry.impure()) {
            return new JavaCallProfile(EffectSet.IMPURE_DECLARED, false, false, mutParams, borrowParams, false);
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
                if (params[i].isAnnotationPresent(Borrow.class)) {
                    borrowParams[i] = true;
                }
                if (params[i].isAnnotationPresent(Unique.class)) {
                    uniqueParams[i] = true;
                }
            }
        }
        EffectSet set = EffectSet.ofDeclared(entry.effects(), mutReceiver, !isStaticOrCtor && borrowAll, entry.uniqueResult(), true);
        return new JavaCallProfile(set, mutReceiver, !isStaticOrCtor && borrowAll, mutParams, borrowParams, uniqueParams, explicitNothrow);
    }

    private static boolean hasAnyEffectAnnotation(Executable exec) {
        if (hasElementEffectAnnotation(exec)) {
            return true;
        }
        for (Parameter p : exec.getParameters()) {
            if (p.isAnnotationPresent(Mut.class) || p.isAnnotationPresent(Borrow.class) || p.isAnnotationPresent(Unique.class)) {
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
                || el.isAnnotationPresent(Hermetic.class)
                || el.isAnnotationPresent(Mut.class)
                || el.isAnnotationPresent(Borrow.class)
                || el.isAnnotationPresent(Unique.class)
                || el.isAnnotationPresent(Deterministic.class)
                || el.isAnnotationPresent(Nothrow.class)
                || el.isAnnotationPresent(Idempotent.class)
                || el.isAnnotationPresent(Commutative.class)
                || el.isAnnotationPresent(Associative.class)
                || el.isAnnotationPresent(Monotone.class)
                || el.isAnnotationPresent(Injective.class)
                || el.isAnnotationPresent(Impure.class);
    }

    private static JavaCallProfile profileFromAnnotations(Executable exec, Class<?> classSource, boolean classLevelOnly) {
        int paramCount = exec != null ? exec.getParameterCount() : 0;
        boolean[] mutParams = new boolean[paramCount];
        boolean[] borrowParams = new boolean[paramCount];
        boolean[] uniqueParams = new boolean[paramCount];
        AnnotatedElement primary = (!classLevelOnly && exec != null) ? exec : classSource;
        if (primary.isAnnotationPresent(Impure.class)) {
            return new JavaCallProfile(EffectSet.IMPURE_DECLARED, false, false, mutParams, borrowParams, uniqueParams, false);
        }
        EnumSet<Effect> effects = EnumSet.noneOf(Effect.class);
        collectAnnotationsInto(classSource, effects);
        if (!classLevelOnly && exec != null) {
            collectAnnotationsInto(exec, effects);
        }
        boolean isStaticOrCtor = exec instanceof Constructor<?> || (exec != null && Modifier.isStatic(exec.getModifiers()));
        boolean mutReceiver = !classLevelOnly && exec instanceof Method && !isStaticOrCtor
                && exec.isAnnotationPresent(Mut.class) && exec.getDeclaringClass() != String.class;
        boolean borrowReceiver = !classLevelOnly && exec != null && exec.isAnnotationPresent(Borrow.class);
        boolean uniqueResult = !classLevelOnly && exec != null && exec.isAnnotationPresent(Unique.class);
        if (exec != null) {
            Parameter[] params = exec.getParameters();
            for (int i = 0; i < params.length; i++) {
                if (params[i].isAnnotationPresent(Mut.class) && params[i].getType() != String.class) {
                    mutParams[i] = true;
                }
                if (params[i].isAnnotationPresent(Borrow.class) || (exec instanceof Constructor<?> && borrowReceiver)) {
                    borrowParams[i] = true;
                }
                if (params[i].isAnnotationPresent(Unique.class)) {
                    uniqueParams[i] = true;
                }
            }
        }
        if (mutReceiver || anyTrue(mutParams)) {
            effects.remove(Effect.PURE);
        }
        boolean explicitNothrow = effects.contains(Effect.NOTHROW);
        EffectSet set = EffectSet.ofDeclared(effects, mutReceiver, !isStaticOrCtor && borrowReceiver, uniqueResult, true);
        return new JavaCallProfile(set, mutReceiver, !isStaticOrCtor && borrowReceiver, mutParams, borrowParams, uniqueParams, explicitNothrow);
    }

    private static void collectAnnotationsInto(AnnotatedElement el, EnumSet<Effect> out) {
        if (el == null || el.isAnnotationPresent(Impure.class)) {
            return;
        }
        if (el.isAnnotationPresent(Pure.class)) {
            out.add(Effect.PURE);
            out.add(Effect.HERMETIC);
        }
        if (el.isAnnotationPresent(Hermetic.class)) {
            out.add(Effect.HERMETIC);
        }
        if (el.isAnnotationPresent(Deterministic.class)) {
            out.add(Effect.DETERMINISTIC);
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

    private static final Set<String> STRING_NOTHROW_METHODS = Set.of(
            "length", "isEmpty", "isBlank", "trim", "strip", "stripLeading", "stripTrailing",
            "toLowerCase", "toUpperCase", "startsWith", "endsWith", "contains", "indexOf",
            "lastIndexOf", "equals", "equalsIgnoreCase", "contentEquals", "compareTo",
            "compareToIgnoreCase", "hashCode", "toString", "concat", "replace");

    private static final Set<String> COLLECTION_READ_METHODS = Set.of(
            "size", "isEmpty", "get", "getOrDefault", "contains", "containsKey",
            "containsValue", "indexOf", "lastIndexOf", "first", "last", "getFirst",
            "getLast", "firstKey", "lastKey", "hasNext", "hasPrevious", "peek",
            "element", "comparator");

    private static final Set<String> COLLECTION_READ_NOTHROW_METHODS = Set.of(
            "size", "isEmpty", "hasNext", "hasPrevious", "comparator");

    private static final Set<String> COLLECTION_VIEW_METHODS = Set.of(
            "iterator", "listIterator", "subList", "keySet", "values", "entrySet",
            "headSet", "tailSet", "subSet", "headMap", "tailMap", "subMap",
            "reversed", "descendingSet", "descendingMap");

    private static final Set<String> COLLECTION_VIEW_NOTHROW_METHODS = Set.of(
            "iterator", "keySet", "values", "entrySet", "reversed",
            "descendingSet", "descendingMap");

    private static final Set<String> COLLECTION_MUT_METHODS = Set.of(
            "add", "addAll", "addFirst", "addLast", "set", "put", "putIfAbsent",
            "putAll", "putFirst", "putLast", "remove", "removeFirst", "removeLast",
            "removeAll", "retainAll", "replace", "replaceAll", "clear", "sort");

    private static final Set<String> COLLECTION_MUT_BORROW_ARG_METHODS = Set.of(
            "addAll", "putAll", "removeAll", "retainAll");

    private static boolean isCoreCollectionClass(Class<?> cls) {
        return cls != null && (java.util.Collection.class.isAssignableFrom(cls)
                || java.util.Map.class.isAssignableFrom(cls)
                || cls == java.lang.Iterable.class
                || java.util.Iterator.class.isAssignableFrom(cls));
    }

    private static boolean isViewMethod(Class<?> owner, String methodName) {
        return isCoreCollectionClass(owner) && COLLECTION_VIEW_METHODS.contains(methodName);
    }

    /** Tier 6 : Standard value protocol & BIP011 core type defaults (bip007_effects.md §4.3, §6.2; bip011 §8.2). */
    private static JavaCallProfile standardProtocolDefault(Class<?> owner, String methodName, Method exactMethod) {
        int paramCount = exactMethod != null ? exactMethod.getParameterCount() : -1;
        if (owner == String.class) {
            boolean isNothrow = STRING_NOTHROW_METHODS.contains(methodName);
            EnumSet<Effect> effs = isNothrow
                    ? EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW)
                    : EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC);
            int pCount = Math.max(paramCount, 0);
            boolean[] borrowParams = new boolean[pCount];
            Arrays.fill(borrowParams, true);
            boolean isStatic = exactMethod != null && Modifier.isStatic(exactMethod.getModifiers());
            EffectSet set = EffectSet.ofInferred(effs, false, !isStatic, false, true);
            return new JavaCallProfile(set, false, !isStatic, new boolean[pCount], borrowParams, isNothrow);
        }
        if (owner != null && java.util.Map.Entry.class.isAssignableFrom(owner)) {
            if (("getKey".equals(methodName) || "getValue".equals(methodName)) && (paramCount == -1 || paramCount == 0)) {
                EffectSet set = EffectSet.ofInferred(
                        EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
                return new JavaCallProfile(set, false, true, new boolean[0], new boolean[0], true);
            }
            if ("setValue".equals(methodName) && (paramCount == -1 || paramCount == 1)) {
                EffectSet set = EffectSet.ofInferred(EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC), true, false, false, true);
                return new JavaCallProfile(set, true, false, new boolean[1], new boolean[1], false);
            }
        }
        if (isCoreCollectionClass(owner)) {
            if (COLLECTION_READ_METHODS.contains(methodName) || COLLECTION_VIEW_METHODS.contains(methodName)) {
                boolean isNothrow = COLLECTION_READ_NOTHROW_METHODS.contains(methodName)
                        || COLLECTION_VIEW_NOTHROW_METHODS.contains(methodName);
                EnumSet<Effect> effs = isNothrow
                        ? EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW)
                        : EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC);
                int pCount = Math.max(paramCount, 0);
                boolean[] borrowParams = new boolean[pCount];
                Arrays.fill(borrowParams, true);
                EffectSet set = EffectSet.ofInferred(effs, false, true, false, true);
                return new JavaCallProfile(set, false, true, new boolean[pCount], borrowParams, isNothrow);
            }
            if (COLLECTION_MUT_METHODS.contains(methodName)) {
                EnumSet<Effect> effs = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC);
                int pCount = Math.max(paramCount, 0);
                boolean[] borrowParams = new boolean[pCount];
                if (COLLECTION_MUT_BORROW_ARG_METHODS.contains(methodName)) {
                    Arrays.fill(borrowParams, true);
                }
                EffectSet set = EffectSet.ofInferred(effs, true, false, false, true);
                return new JavaCallProfile(set, true, false, new boolean[pCount], borrowParams, false);
            }
        }
        if ("equals".equals(methodName) && (paramCount == -1 || paramCount == 1)) {
            EffectSet set = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, true, false, true);
            return new JavaCallProfile(set, false, true, new boolean[]{false}, new boolean[]{true}, false);
        }
        if ("compareTo".equals(methodName) && (paramCount == -1 || paramCount == 1)
                && (owner != null && Comparable.class.isAssignableFrom(owner))) {
            EffectSet set = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, true, false, true);
            return new JavaCallProfile(set, false, true, new boolean[]{false}, new boolean[]{true}, false);
        }
        if (("hashCode".equals(methodName) || "toString".equals(methodName)) && (paramCount == -1 || paramCount == 0)) {
            Class<?> decl = resolveDeclaringClass(owner, methodName);
            boolean deterministic = isCoreCollectionClass(owner)
                    || (owner != null && java.util.Map.Entry.class.isAssignableFrom(owner))
                    || (decl != null && decl != Object.class && !decl.isInterface());
            EnumSet<Effect> effs = deterministic
                    ? EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC)
                    : EnumSet.of(Effect.PURE, Effect.HERMETIC);
            EffectSet set = EffectSet.ofInferred(effs, false, true, false, true);
            return new JavaCallProfile(set, false, true, new boolean[0], new boolean[0], false);
        }
        return null;
    }

    private static Class<?> resolveDeclaringClass(Class<?> owner, String methodName) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(methodName).getDeclaringClass();
        } catch (NoSuchMethodException e) {
            return Object.class;
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
            updated.add(new Type.Parameter(p.name(), p.type(), p.optional(), mut, borrow, borrow, unique));
        }
        EffectSet fnEffects = new EffectSet(profile.effects().effects(), profile.effects().effects(),
                false, false, false,
                profile.effects().uniqueResult(), profile.effects().explicitUniqueResult(),
                profile.effects().terminating(), false, false);
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

    private boolean isCompletelyUntyped(BabbleParser.FunctionLiteralContext fn) {
        ParsedPrefix prefix = parseFunctionPrefix(fn, false);
        if (prefix.hasAnyModifier() || fn.type() != null) {
            return false;
        }
        for (BabbleParser.FnParamContext param : fn.fnParameters().fnParam()) {
            ParamMods pm = parseParamMods(param, false);
            if (param.type() != null || pm.mut() || pm.borrow()) {
                return false;
            }
        }
        return true;
    }

    private boolean isFixedSummary(ParserRuleContext callable) {
        if (callable instanceof BabbleParser.FunctionLiteralContext fn) {
            ParsedPrefix prefix = parseFunctionPrefix(fn, false);
            return prefix.axiom() || prefix.impure();
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
                borrowParams[i] = pm.borrow();
                uniqueParams[i] = pm.unique();
            }
            if (prefix.impure()) {
                return new CallableSummary(EffectSet.IMPURE_DECLARED, mutParams, new boolean[n], uniqueParams);
            }
            if (prefix.axiom()) {
                EffectSet set = prefix.toEffectSet();
                return new CallableSummary(set, mutParams, borrowParams, uniqueParams);
            }
            // Optimistic start for inference/verification: {HERMETIC, DETERMINISTIC, NOTHROW}, borrow all, uniqueResult, @TERMINATING!
            for (int i = 0; i < n; i++) {
                borrowParams[i] = !uniqueParams[i] || parseParamMods(params.get(i), false).borrow();
            }
            EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
            boolean borrowThis = canReferenceThis(checker.scopeOf(fn));
            EffectSet set = new EffectSet(opt, prefix.effects(), prefix.mutThis(), borrowThis, prefix.borrowThis(),
                    true, prefix.uniqueReturn(), true, false, false);
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
        for (int i = 0; i < n; i++) {
            ParamMods pm = parseParamMods(params.get(i), true);
            if (isValue && pm.mut()) {
                checker.effectDiagnostic("value class constructor parameter cannot be mut : " + params.get(i).ID().getText(), params.get(i));
            }
            mutParams[i] = !isValue && pm.mut();
            uniqueParams[i] = pm.unique();
            // Field parameters (`def` or `value class`) are stored in `this`, so non-immutable ones escape into the new instance!
            boolean storedAsField = isValue || params.get(i).DEF() != null;
            Type pType = checker.typeOfDeclarationForEffects(params.get(i));
            borrowParams[i] = (!uniqueParams[i] || pm.borrow()) && (!storedAsField || isDeeplyImmutable(pType));
        }
        EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
        return new CallableSummary(EffectSet.ofInferred(opt, false, false, true, true), mutParams, borrowParams, uniqueParams);
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
        int n = params.size();
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
                prefix.borrowThis(),
                analyzer.uniqueResult,
                prefix.uniqueReturn(),
                analyzer.terminating,
                false,
                false);
        return new CallableSummary(nextSet, analyzer.mutParams, analyzer.borrowParams, current.uniqueParams(),
                Set.copyOf(analyzer.frameReadOwners), Set.copyOf(analyzer.frameWriteOwners));
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
                ? new ParsedPrefix(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, false, false, false, true)
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
            if ((isValue || p.DEF() != null) && !isDeeplyImmutable(checker.typeOfDeclarationForEffects(p))) {
                analyzer.markParamEscaped(i, "class field " + p.ID().getText(), p);
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
        EffectSet nextSet = EffectSet.ofInferred(nextEffects, false, false, analyzer.hermetic, analyzer.terminating);
        return new CallableSummary(nextSet, analyzer.mutParams, analyzer.borrowParams, current.uniqueParams());
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
                raw.effects().explicitBorrowThis(),
                raw.effects().uniqueResult(),
                raw.effects().explicitUniqueResult(),
                raw.effects().terminating(),
                false,
                false);
        return new CallableSummary(finalized, raw.mutParams(), raw.borrowParams(), raw.uniqueParams(),
                raw.frameReadOwners(), raw.frameWriteOwners());
    }

    private CallableSummary buildVisibleSummary(ParserRuleContext callable, CallableSummary internal) {
        if (!(callable instanceof BabbleParser.FunctionLiteralContext fn)) {
            return internal;
        }
        ParsedPrefix prefix = parseFunctionPrefix(fn, false);
        List<BabbleParser.FnParamContext> params = fn.fnParameters().fnParam();
        boolean hasAnyExplicit = prefix.hasAnyModifier();
        for (BabbleParser.FnParamContext p : params) {
            ParamMods pm = parseParamMods(p, false);
            hasAnyExplicit |= pm.mut() || pm.borrow() || pm.unique();
        }
        if (!hasAnyExplicit) {
            // Unannotated function (including unannotated `export def` per BIP007-D2):
            // expose full inferred EffectSet, inferred borrow, and (for untyped fns) inferred mut!
            return internal;
        }
        if (prefix.axiom() || prefix.impure()) {
            return internal;
        }
        // Annotated function: lock caller-visible contract to declared modifiers (BIP007-D2, §5.1.5),
        // while preserving compiler-internal @TERMINATING metadata (BIP007-D10).
        boolean[] declMut = new boolean[params.size()];
        boolean[] declBorrow = new boolean[params.size()];
        boolean[] declUnique = new boolean[params.size()];
        for (int i = 0; i < params.size(); i++) {
            ParamMods pm = parseParamMods(params.get(i), false);
            declMut[i] = pm.mut();
            declBorrow[i] = pm.borrow();
            declUnique[i] = pm.unique();
        }
        EffectSet rawDecl = prefix.toEffectSet();
        EffectSet declSet = new EffectSet(
                rawDecl.effects(), rawDecl.declared(),
                rawDecl.mutThis(), rawDecl.borrowThis(), rawDecl.explicitBorrowThis(),
                rawDecl.uniqueResult(), rawDecl.explicitUniqueResult(),
                internal.effects().terminating(),
                rawDecl.impure(), rawDecl.axiomatic());
        return new CallableSummary(declSet, declMut, declBorrow, declUnique);
    }

    // ================================================================ 8. Verification Pass

    void verify(BabbleParser.FileContext file) {
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
        if (tree instanceof BabbleParser.FunctionTypeContext ft) {
            Type t = checker.annotationTypeOf(ft);
            if (t instanceof Type.Function fn) {
                ParsedPrefix prefix = parseFunctionTypePrefix(ft, false);
                validateAlgebraicSignature(prefix.effects(), fn.parameters(), fn.result(),
                        prefix.mutThis(), false, ft);
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            validateAllFunctionTypes(tree.getChild(i));
        }
    }

    private void verifyCallable(ParserRuleContext callable) {
        if (callable instanceof BabbleParser.FunctionLiteralContext fn) {
            ParsedPrefix prefix = parseFunctionPrefix(fn, false);
            Type rawType = checker.rawTypeOf(fn);
            if (rawType instanceof Type.Function tf && !prefix.impure()) {
                validateAlgebraicSignature(prefix.effects(), tf.parameters(), tf.result(),
                        prefix.mutThis(), prefix.axiom(), fn);
            }
            if (prefix.axiom()) {
                // Axiom body is trusted for effect guarantees, but still check static mut exclusivity inside it!
                recomputeSummary(fn, summaries.get(fn), null, false);
                return;
            }
            // Re-run body analysis with emitDiagnostics = true to report undeclared mut, borrow escape, exclusivity, etc.
            CallableSummary converged = summaries.get(fn);
            recomputeSummary(fn, converged, null, true);

            if (!prefix.impure() && prefix.hasAnyModifier()) {
                // Check each declared positive effect!
                EffectSet actual = converged.effects();
                for (Effect req : prefix.effects()) {
                    if (req == Effect.HERMETIC && prefix.effects().contains(Effect.PURE)) {
                        continue;
                    }
                    if (!actual.has(req)) {
                        String fnLabel = ScopeAnalysis.functionName(fn);
                        String prefixMsg = fnLabel != null ? "Function " + fnLabel : "Function";
                        checker.effectDiagnostic(prefixMsg + " declared " + EffectSet.keywordOf(req)
                                + " violates " + EffectSet.keywordOf(req) + " guarantee", fn);
                    }
                }
            }
            if (!prefix.impure() && prefix.uniqueReturn() && !converged.effects().uniqueResult()) {
                String fnLabel = ScopeAnalysis.functionName(fn);
                String prefixMsg = fnLabel != null ? "Function " + fnLabel : "Function";
                checker.effectDiagnostic(prefixMsg + " declared unique return violates unique guarantee", fn);
            }
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
                false, false, false, false, !constraints.isEmpty());
        BodyAnalyzer ba = new BodyAnalyzer(
                block, scope, List.of(), false,
                new boolean[0], new boolean[0], new boolean[0], false, false,
                req, Set.of(), null, true);
        ba.evalBlock(block, false, true);
        if (constraints.contains(Effect.PURE) && !ba.hermetic) {
            checker.effectDiagnostic("static block inside pure function violates pure guarantee", block);
        } else if (constraints.contains(Effect.HERMETIC) && !ba.hermetic) {
            checker.effectDiagnostic("static block inside hermetic function violates hermetic guarantee", block);
        }
        if (constraints.contains(Effect.DETERMINISTIC) && !ba.deterministic) {
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
        if (type instanceof Type.Union u) {
            return u.members().stream().allMatch(m -> isDeeplyImmutable(m, visiting));
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
        if (type instanceof Type.Function || type instanceof Type.ClassValue
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
            // Non-value BabbleInstance default toString uses identity hash -> pure only (not deterministic)!
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.NOTHROW), false, true, false, true);
        }
        if (type instanceof Type.JavaInstance ji) {
            return resolveJavaMethodProfile(ji.type(), "toString", null).effects();
        }
        if (type instanceof Type.JavaArray) {
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.NOTHROW), false, true, false, true);
        }
        return EffectSet.EMPTY;
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
            // Regular BabbleInstance uses `==` reference identity (pure deterministic nothrow)!
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
        }
        if (type instanceof Type.JavaInstance ji) {
            return resolveJavaMethodProfile(ji.type(), "equals", null).effects();
        }
        return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, true, false, true);
    }

    private EffectSet comparisonProfile(Type left, Type right) {
        if (isPrimitiveOrString(left) && isPrimitiveOrString(right)) {
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
        }
        if (left instanceof Type.JavaInstance ji) {
            return resolveJavaMethodProfile(ji.type(), "compareTo", null).effects();
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
        private boolean stmtHasSideEffect = false;
        private boolean stmtHasError = false;

        final Set<BabbleParser.FunctionLiteralContext> frameReadOwners = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<BabbleParser.FunctionLiteralContext> frameWriteOwners = Collections.newSetFromMap(new IdentityHashMap<>());

        private Map<ParserRuleContext, Origin> env = new IdentityHashMap<>();
        private final Set<String> reportedMessages = new HashSet<>();

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
            this.mutThis = mutThis;
            this.borrowThis = borrowThis;
            this.declaredPrefix = declaredPrefix;
            this.frameChain = frameChain;
            this.specializedParams = specializedParams;
            this.emitDiagnostics = emitDiagnostics;
            this.terminating = !cyclicCallables.contains(owner);

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

        private void demoteUniqueOwner(Object ownerKey, Origin taint) {
            if (ownerKey instanceof ParserRuleContext decl) {
                Origin cur = env.get(decl);
                if (cur != null && cur.unique) {
                    env.put(decl, cur.demoteToExternal(taint));
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
            if (rhs.unique && rhs.uniqueOwner != null) {
                // Aliasing a live unique local without move(...) demotes both to @EXTERNAL!
                demoteUniqueOwner(rhs.uniqueOwner, Origin.IMMUTABLE);
                return withLocalAliasKey(rhs.demoteToExternal(Origin.IMMUTABLE), decl, localType);
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
                    || stmt instanceof BabbleParser.AssignExpressionContext
                    || stmt instanceof BabbleParser.ReturnExpressionContext
                    || stmt instanceof BabbleParser.WhileExpressionContext) {
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
            eval(stmt);
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
                checkClosureCaptureEscape(lit);
                return Origin.IMMUTABLE;
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
            recordTargetMutation(recvOrig, recv, "field assignment ." + assignedName, assign);
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
                    if (paramIndex.containsKey(decl)) {
                        Origin orig = env.getOrDefault(decl, Origin.ofParam(paramIndex.get(decl), decl, imm));
                        if (orig.moved) {
                            reportOnce("Use of moved variable " + sel.ID().getText(), sel);
                        }
                        return orig;
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
                        return orig;
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
                if (Modifier.isStatic(jf.getModifiers()) && !Modifier.isFinal(jf.getModifiers())) {
                    deterministic = false;
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
            return recvOrig.deriveMember(fieldName, immRes);
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
            BabbleParser.ExpressionContext unwrappedCallee = calleeExpr instanceof BabbleParser.TypeApplyContext ta
                    ? ta.expression() : calleeExpr;
            // Evaluate receiver / callee expression and arguments!
            Origin recvOrig = Origin.IMMUTABLE;
            BabbleParser.ExpressionContext receiverExpr = null;
            if (unwrappedCallee instanceof BabbleParser.SelectorContext sel && sel.expression() != null) {
                receiverExpr = sel.expression();
                Type recvType = effectiveType(receiverExpr);
                if (!(recvType instanceof Type.JavaPackage || recvType instanceof Type.Module)) {
                    recvOrig = eval(receiverExpr);
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
            Type calleeType = resolveCalleeType(calleeExpr);

            // 1. Built-in `print` / `println` / `import` / scalar conversions
            if (calleeExpr instanceof BabbleParser.CoreScalarRefContext) {
                return evalCoreConversionCall(resType, argExprs);
            }
            if (calleeType == Type.PRINT) {
                hermetic = false;
                deterministic = false;
                nothrow = false;
                terminating = false;
                return Origin.IMMUTABLE;
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
                        rm.getParameterTypes(), instanceRecv, recvOrig, argExprs, argOrigins, resType);
            }
            if (calleeType instanceof Type.JavaMethod jm) {
                JavaCallProfile profile = resolveJavaMethodProfile(jm.owner(), jm.name(), null);
                Class<?>[] samTypes = findOverloadSamTypes(jm.owner(), jm.name(), args.size());
                boolean isClassRecv = receiverExpr != null && checker.rawTypeOf(receiverExpr) instanceof Type.JavaClassValue;
                return applyJavaCall(call, calleeExpr, profile, jm.owner(), jm.name(),
                        samTypes, isClassRecv ? null : receiverExpr, recvOrig, argExprs, argOrigins, resType);
            }

            // 3. Java constructor call or Java functional interface SAM conversion `Predicate(fn)`
            Constructor<?> rc = checker.resolvedJavaConstructorOf(call);
            if (rc != null) {
                JavaCallProfile profile = resolveJavaConstructorProfile(rc.getDeclaringClass(), rc);
                return applyJavaCall(call, calleeExpr, profile, rc.getDeclaringClass(), "<init>",
                        rc.getParameterTypes(), null, Origin.IMMUTABLE, argExprs, argOrigins, resType);
            }
            if (calleeType instanceof Type.JavaClassValue jcv) {
                if (jcv.type().isInterface() && args.size() == 1 && JavaTypes.isFunctionalInterface(jcv.type())) {
                    // Explicit SAM wrapper `Function(fn)` is pure deterministic nothrow itself (constructing proxy).
                    return argOrigins[0];
                }
                JavaCallProfile profile = resolveJavaConstructorProfile(jcv.type(), null);
                return applyJavaCall(call, calleeExpr, profile, jcv.type(), "<init>",
                        null, null, Origin.IMMUTABLE, argExprs, argOrigins, resType);
            }

            // 4. Babble class constructor call `Point(1, 2)`
            if (calleeType instanceof Type.ClassValue cv && !cv.node().isInterface()) {
                CallableSummary cs = constructorSummary(cv.node());
                boolean ctorUnique = cs != null && cs.effects().isHermetic();
                if (cs != null) {
                    applyEffects(cs.effects());
                    List<Type.Parameter> cParams = checker.constructorParamsForEffects(cv.node());
                    for (int i = 0; i < args.size(); i++) {
                        int pIdx = checker.parameterIndexForArg(cParams, args, args.get(i));
                        boolean borrowed = pIdx >= 0 && pIdx < cs.borrowParams().length && cs.borrowParams()[pIdx];
                        boolean uniqueP = pIdx >= 0 && pIdx < cs.uniqueParams().length && cs.uniqueParams()[pIdx];
                        if (!borrowed && !uniqueP && !isUnattachedUniqueOrImmutable(argOrigins[i], effectiveType(argExprs[i]))) {
                            ctorUnique = false;
                        }
                    }
                    applyBabbleArgEffects(call, calleeExpr, cParams, cs.mutParams(), cs.borrowParams(), cs.uniqueParams(),
                            null, Origin.IMMUTABLE, false, true, args, argExprs, argOrigins);
                }
                boolean imm = isDeeplyImmutable(resType);
                return ctorUnique ? Origin.ofUnique(call, null, imm) : Origin.ofExternal(call, imm);
            }

            // 5. Babble `recurse(...)`
            if (calleeExpr instanceof BabbleParser.RecurseContext && owner instanceof BabbleParser.FunctionLiteralContext selfFn) {
                CallableSummary selfSum = summaries.getOrDefault(selfFn, initialSummary(selfFn));
                applyCallableSummaryEffects(selfSum);
                Type selfRaw = checker.rawTypeOf(selfFn);
                List<Type.Parameter> fParams = selfRaw instanceof Type.Function tf ? tf.parameters() : List.of();
                boolean hasRecv = canReferenceThis(ownerScope);
                Origin selfRecv = hasRecv ? Origin.ofThis(THIS_KEY, false) : Origin.IMMUTABLE;
                applyBabbleArgEffects(call, calleeExpr, fParams, selfSum.mutParams(), selfSum.borrowParams(), selfSum.uniqueParams(),
                        null, selfRecv, selfSum.effects().mutThis(), selfSum.effects().borrowThis(),
                        args, argExprs, argOrigins);
                if (selfSum.effects().uniqueResult()) {
                    return Origin.ofUnique(call, null, isDeeplyImmutable(resType));
                }
                return deriveCallReturnOrigin(resType, selfRecv, selfSum.effects().borrowThis(), argOrigins, selfSum.borrowParams(), fParams, args);
            }

            // 6. Babble function / method / union of functions
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
                for (int i = 0; i < argExprs.length; i++) {
                    Type argType = effectiveType(argExprs[i]);
                    if (!isDeeplyImmutable(argType)) {
                        immutableEntry = false;
                        if (!isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                            uniqueEntry = false;
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
                    return uniqueEntry ? Origin.ofUnique(ccc, null, false) : Origin.ofExternal(ccc, false);
                }
                return Origin.IMMUTABLE;
            }

            // list, set, sortedset, map, sortedmap: hermetic deterministic allocation (unique per BIP007-D6!)
            applyEffects(EffectSet.ofInferred(EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true));
            boolean hasCapacity = false;
            boolean hasFrom = false;
            boolean hasComparator = false;
            boolean hasElements = false;
            boolean staysUnique = true;
            for (int i = 0; i < paramsList.size(); i++) {
                BabbleParser.CallParameterContext p = paramsList.get(i);
                String name = p.memberName() != null ? p.memberName().getText() : null;
                if ("capacity".equals(name)) {
                    hasCapacity = true;
                } else if ("from".equals(name)) {
                    hasFrom = true;
                    // `from:` borrows the source collection; result is unique iff copied elements/keys+values are deeply immutable (BIP007-D6).
                    Type fromType = effectiveType(argExprs[i]);
                    if (fromType instanceof Type.Frozen f) {
                        fromType = f.of();
                    }
                    if (fromType instanceof Type.JavaInstance ji && !ji.typeArguments().isEmpty()) {
                        for (Type ta : ji.typeArguments()) {
                            if (!isDeeplyImmutable(ta)) {
                                staysUnique = false;
                            }
                        }
                    } else {
                        staysUnique = false;
                    }
                } else if ("comparator".equals(name)) {
                    hasComparator = true;
                    markStoredOrigin(argOrigins[i], kind + " comparator", argExprs[i]);
                    Type cmpType = effectiveType(argExprs[i]);
                    propagateSamCallbackEffects(cmpType, java.util.Comparator.class);
                    if (!(cmpType instanceof Type.Function cmpFn)
                            || !cmpFn.effects().isPure() || !cmpFn.effects().isDeterministic()) {
                        staysUnique = false;
                    }
                } else {
                    hasElements = true;
                    Type argType = effectiveType(argExprs[i]);
                    if (!isDeeplyImmutable(argType)) {
                        if (!isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                            staysUnique = false;
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
            return staysUnique ? Origin.ofUnique(ccc, null, false) : Origin.ofExternal(ccc, false);
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
                    Class<?>[] pts = m.getParameterTypes();
                    for (int i = 0; i < arity; i++) {
                        if (JavaTypes.isFunctionalInterface(pts[i])) {
                            res[i] = pts[i];
                        }
                    }
                }
            }
            return res;
        }

        private Origin applyJavaCall(ParserRuleContext call,
                                     BabbleParser.ExpressionContext calleeExpr,
                                     JavaCallProfile profile,
                                     Class<?> owner,
                                     String methodName,
                                     Class<?>[] javaParamTypes,
                                     BabbleParser.ExpressionContext receiverExpr,
                                     Origin recvOrig,
                                     BabbleParser.ExpressionContext[] argExprs,
                                     Origin[] argOrigins,
                                     Type resType) {
            applyEffects(profile.effects());
            if (checker.hasCallCheck(call) && !profile.explicitNothrow()) {
                nothrow = false;
            }

            // Receiver mut / borrow / frozen check:
            Type recvType = receiverExpr != null ? effectiveType(receiverExpr) : null;
            boolean effectiveMutRecv = profile.mutReceiver() && receiverExpr != null && recvType != Type.STRING;
            if (receiverExpr != null && containsFrozen(recvType)) {
                if (effectiveMutRecv) {
                    reportOnce("Cannot call mut method " + methodName + " on frozen value", call);
                } else if (!profile.borrowReceiver() && !profile.effects().isPure()) {
                    reportOnce("Cannot call non-borrow method " + methodName + " on frozen value", call);
                }
            }
            if (effectiveMutRecv) {
                recordTargetMutation(recvOrig, receiverExpr, "mut Java method " + calleeExpr.getText(), call);
            }
            boolean recvCanEscape = !profile.borrowReceiver() && !profile.effects().isPure()
                    && !(profile.effects().isHermetic() && !anyTrue(profile.mutParams()) && isDeeplyImmutable(resType));
            if (receiverExpr != null && recvCanEscape) {
                if (recvOrig.unique && recvOrig.uniqueOwner != null) {
                    demoteUniqueOwner(recvOrig.uniqueOwner, Origin.IMMUTABLE);
                }
                markStoredOrigin(recvOrig, "Java method call", call);
            }

            // Parameter mut / borrow / unique / frozen / SAM callback checks:
            boolean[] argIsMut = new boolean[argExprs.length];
            for (int i = 0; i < argExprs.length; i++) {
                Type argType = effectiveType(argExprs[i]);
                boolean mutP = i < profile.mutParams().length && profile.mutParams()[i] && argType != Type.STRING;
                boolean borrowP = (i < profile.borrowParams().length && profile.borrowParams()[i]) || profile.effects().isPure();
                boolean uniqueP = i < profile.uniqueParams().length && profile.uniqueParams()[i];
                argIsMut[i] = mutP;
                if (containsFrozen(argType)) {
                    if (mutP) {
                        reportOnce("Cannot pass frozen value to mut parameter", argExprs[i]);
                    } else if (!borrowP) {
                        reportOnce("Cannot pass frozen value to non-borrow Java parameter", argExprs[i]);
                    }
                }
                if (uniqueP && !argOrigins[i].moved) {
                    if (!argOrigins[i].unique && argOrigins[i] != Origin.IMMUTABLE && !isDeeplyImmutable(argType) && argType != Type.ERROR) {
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
                    recordTargetMutation(argOrigins[i], argExprs[i], "mut Java parameter", argExprs[i]);
                }
                if (!borrowP) {
                    // Storing a non-unique, non-immutable arg into a unique receiver (e.g. xs.add(shared)) demotes receiver!
                    if (recvOrig.unique && effectiveMutRecv && !isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                        demoteUniqueOwner(recvOrig.uniqueOwner, argOrigins[i]);
                    }
                    if (!uniqueP && argOrigins[i].unique && argOrigins[i].uniqueOwner != null) {
                        demoteUniqueOwner(argOrigins[i].uniqueOwner, Origin.IMMUTABLE);
                    }
                    markStoredOrigin(argOrigins[i], "Java call argument", argExprs[i]);
                }
                // Java SAM callback static effect propagation (§6.3):
                if (javaParamTypes != null && i < javaParamTypes.length && javaParamTypes[i] != null
                        && JavaTypes.isFunctionalInterface(javaParamTypes[i])) {
                    propagateSamCallbackEffects(argType, javaParamTypes[i]);
                }
            }

            // Static call-site mut exclusivity:
            checkCallExclusivity(receiverExpr, recvOrig, effectiveMutRecv, argExprs, argOrigins, argIsMut, call);

            // Result origin:
            if (isDeeplyImmutable(resType)) {
                return Origin.IMMUTABLE;
            }
            if (profile.effects().uniqueResult()) {
                return Origin.ofUnique(call, null, false);
            }
            if (receiverExpr != null && isViewMethod(owner, methodName)) {
                return recvOrig.deriveMember("$" + methodName, false);
            }
            Origin out = Origin.ofExternal(call, false);
            if ("<init>".equals(methodName) || isCollectionFactoryMethod(owner, methodName)) {
                return out;
            }
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
                CallableSummary summary = resolveBabbleCallSummary(fn, calleeExpr, receiverExpr, args, argExprs);
                applyCallableSummaryEffects(summary);
                boolean isMethodCall = receiverExpr != null || calleeExpr instanceof BabbleParser.SuperExpressionContext
                        || (calleeExpr instanceof BabbleParser.SelectorContext s && s.expression() instanceof BabbleParser.SuperExpressionContext);
                Origin effectiveRecvOrig = (calleeExpr instanceof BabbleParser.SelectorContext s && s.expression() instanceof BabbleParser.SuperExpressionContext)
                        ? Origin.ofThis(THIS_KEY, false) : recvOrig;
                boolean effectiveBorrowRecv = !isMethodCall || summary.effects().borrowThis()
                        || (summary.effects().isHermetic() && !anyTrue(summary.mutParams()) && isDeeplyImmutable(resType));
                applyBabbleArgEffects(call, calleeExpr, fn.parameters(), summary.mutParams(), summary.borrowParams(), summary.uniqueParams(),
                        isMethodCall ? receiverExpr : null, effectiveRecvOrig,
                        isMethodCall && summary.effects().mutThis(),
                        effectiveBorrowRecv,
                        args, argExprs, argOrigins);
                Origin memberOut;
                if (summary.effects().uniqueResult()) {
                    memberOut = Origin.ofUnique(call, null, isDeeplyImmutable(resType));
                } else {
                    memberOut = deriveCallReturnOrigin(resType, effectiveRecvOrig,
                            !isMethodCall || summary.effects().borrowThis(),
                            argOrigins, summary.borrowParams(), fn.parameters(), args);
                }
                combinedOut = combinedOut == Origin.IMMUTABLE ? memberOut : combinedOut.join(memberOut);
            }
            return combinedOut;
        }

        private CallableSummary resolveBabbleCallSummary(Type.Function fn,
                                                         BabbleParser.ExpressionContext calleeExpr,
                                                         BabbleParser.ExpressionContext receiverExpr,
                                                         List<BabbleParser.CallParameterContext> args,
                                                         BabbleParser.ExpressionContext[] argExprs) {
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
            if (targetLit != null) {
                ParsedPrefix targetPrefix = parseFunctionPrefix(targetLit, false);
                if (!targetPrefix.impure() && !targetPrefix.axiom()) {
                    // Bounded call-site specialization when caller passes concrete callback arguments (§4.5)!
                    CallableSummary specialized = trySpecializeCall(targetLit, fn.parameters(), args, argExprs, EffectChecker.this);
                    if (specialized != null) {
                        return lockToDeclaredIfAnnotated(targetLit, EffectChecker.this, specialized);
                    }
                }
                CallableSummary base = summaries.get(targetLit);
                if (base != null) {
                    return !inSameSccOrUnannotated(targetLit, targetPrefix)
                            ? visibleSummaries.getOrDefault(targetLit, base)
                            : base;
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
            for (int i = 0; i < n; i++) {
                mp[i] = fn.parameters().get(i).mut();
                bp[i] = fn.parameters().get(i).borrow();
                up[i] = fn.parameters().get(i).unique();
            }
            return new CallableSummary(fn.effects(), mp, bp, up);
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

        private boolean inSameSccOrUnannotated(BabbleParser.FunctionLiteralContext lit, ParsedPrefix prefix) {
            return !prefix.hasAnyModifier() || prefix.axiom() || prefix.impure();
        }

        private CallableSummary lockToDeclaredIfAnnotated(BabbleParser.FunctionLiteralContext lit,
                                                          EffectChecker targetEC,
                                                          CallableSummary specialized) {
            CallableSummary vis = targetEC.visibleSummaries.get(lit);
            if (vis != null && vis.effects().hasExplicitAnnotations()) {
                return vis;
            }
            return specialized;
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

        private void applyBabbleArgEffects(ParserRuleContext call,
                                           BabbleParser.ExpressionContext calleeExpr,
                                           List<Type.Parameter> calleeParams,
                                           boolean[] calleeMut,
                                           boolean[] calleeBorrow,
                                           boolean[] calleeUnique,
                                           BabbleParser.ExpressionContext receiverExpr,
                                           Origin recvOrig,
                                           boolean mutRecv,
                                           boolean borrowRecv,
                                           List<BabbleParser.CallParameterContext> args,
                                           BabbleParser.ExpressionContext[] argExprs,
                                           Origin[] argOrigins) {
            if (receiverExpr != null && containsFrozen(effectiveType(receiverExpr))) {
                if (mutRecv) {
                    reportOnce("Cannot call mut method on frozen value", call);
                } else if (!borrowRecv) {
                    reportOnce("Cannot call non-borrow method on frozen value", call);
                }
            }
            boolean effectiveMutRecv = mutRecv && (receiverExpr != null || !recvOrig.isEmpty())
                    && (receiverExpr == null || effectiveType(receiverExpr) != Type.STRING);
            if (effectiveMutRecv) {
                recordTargetMutation(recvOrig, receiverExpr != null ? receiverExpr : call,
                        "mut method " + calleeExpr.getText(), call);
            }
            if (!borrowRecv) {
                if (recvOrig.unique && recvOrig.uniqueOwner != null) {
                    demoteUniqueOwner(recvOrig.uniqueOwner, Origin.IMMUTABLE);
                }
                markStoredOrigin(recvOrig, "method call " + calleeExpr.getText(), call);
            }

            boolean[] argIsExclusive = new boolean[args.size()];
            for (int i = 0; i < args.size(); i++) {
                int pIdx = checker.parameterIndexForArg(calleeParams, args, args.get(i));
                Type argType = effectiveType(argExprs[i]);
                boolean mutP = pIdx >= 0 && pIdx < calleeMut.length && calleeMut[pIdx] && argType != Type.STRING;
                boolean borrowP = pIdx >= 0 && pIdx < calleeBorrow.length && calleeBorrow[pIdx];
                boolean uniqueP = pIdx >= 0 && pIdx < calleeUnique.length && calleeUnique[pIdx];
                argIsExclusive[i] = mutP || (uniqueP && borrowP);
                if (uniqueP && !argOrigins[i].moved) {
                    String pName = pIdx >= 0 && pIdx < calleeParams.size() ? calleeParams.get(pIdx).name() : ("#" + i);
                    if (!argOrigins[i].unique && argOrigins[i] != Origin.IMMUTABLE && !isDeeplyImmutable(argType) && argType != Type.ERROR) {
                        reportOnce("Argument for unique parameter " + pName + " must be unique", argExprs[i]);
                    } else if (!borrowP && argOrigins[i].unique && argOrigins[i].uniqueOwner instanceof ParserRuleContext decl) {
                        if (paramIndex.containsKey(decl) && borrowParams[paramIndex.get(decl)]) {
                            reportOnce("Cannot move borrowed parameter " + argExprs[i].getText(), argExprs[i]);
                        } else {
                            env.put(decl, Origin.MOVED);
                        }
                    }
                }
                if (mutP) {
                    recordTargetMutation(argOrigins[i], argExprs[i],
                            "mut parameter " + calleeParams.get(pIdx).name(), argExprs[i]);
                }
                if (!borrowP) {
                    if (recvOrig.unique && effectiveMutRecv && !isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                        demoteUniqueOwner(recvOrig.uniqueOwner, argOrigins[i]);
                    }
                    if (!uniqueP && argOrigins[i].unique && argOrigins[i].uniqueOwner != null) {
                        demoteUniqueOwner(argOrigins[i].uniqueOwner, Origin.IMMUTABLE);
                    }
                    markStoredOrigin(argOrigins[i], "call to " + calleeExpr.getText(), argExprs[i]);
                }
            }

            checkCallExclusivity(receiverExpr, recvOrig, effectiveMutRecv, argExprs, argOrigins, argIsExclusive, call);
        }

        private Origin deriveCallReturnOrigin(Type resType,
                                              Origin recvOrig,
                                              boolean borrowRecv,
                                              Origin[] argOrigins,
                                              boolean[] calleeBorrow,
                                              List<Type.Parameter> calleeParams,
                                              List<BabbleParser.CallParameterContext> args) {
            if (isDeeplyImmutable(resType)) {
                return Origin.IMMUTABLE;
            }
            Origin out = Origin.ofExternal(null, false);
            if (!borrowRecv) {
                out = out.join(recvOrig.stripDirectIdentity(false));
            }
            for (int i = 0; i < args.size(); i++) {
                int pIdx = checker.parameterIndexForArg(calleeParams, args, args.get(i));
                boolean borrowP = pIdx >= 0 && pIdx < calleeBorrow.length && calleeBorrow[pIdx];
                if (!borrowP) {
                    out = out.join(argOrigins[i].stripDirectIdentity(false));
                }
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

        private void recordTargetMutation(Origin target, ParserRuleContext targetExpr, String reason, ParserRuleContext at) {
            stmtHasSideEffect = true;
            if (targetExpr instanceof BabbleParser.ExpressionContext expr && effectiveType(expr) == Type.ERROR) {
                return;
            }
            boolean hitParamOrThis = false;
            if (target.aliasesThis) {
                hitParamOrThis = true;
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
            for (int p = target.params.nextSetBit(0); p >= 0; p = target.params.nextSetBit(p + 1)) {
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
            // In BIP007-P2, mutating an owned `unique` local or consuming `unique` param stays confined (hermetic/pure)!
            if (target.unique && !target.external && !hitParamOrThis) {
                return;
            }
            if (target.external || !hitParamOrThis) {
                hermetic = false;
                if (isConstrainedPureOrHermetic()) {
                    reportOnce("Cannot mutate object in " + (declaredPrefix.effects().contains(Effect.PURE) ? "pure" : "hermetic")
                            + " function (" + reason + ")", at);
                }
            }
        }

        private void markStoredIntoTarget(Origin target, Origin value, Type valueType, String fieldName, ParserRuleContext at) {
            if (value == Origin.IMMUTABLE) {
                return;
            }
            if (target.unique && !isUnattachedUniqueOrImmutable(value, valueType)) {
                demoteUniqueOwner(target.uniqueOwner, value);
            }
            if (value.unique && value.uniqueOwner != null) {
                demoteUniqueOwner(value.uniqueOwner, Origin.IMMUTABLE);
            }
            // Even storing into a `mut` parameter or `this` escapes a `borrow` value!
            markStoredOrigin(value, "field ." + fieldName, at);
        }

        private void markStoredOrigin(Origin value, String destination, ParserRuleContext at) {
            if (value == Origin.IMMUTABLE) {
                return;
            }
            if (value.aliasesThis && borrowThis) {
                borrowThis = false;
                if (declaredPrefix.borrowThis()) {
                    reportOnce("borrow receiver this escapes via " + destination, at);
                }
            }
            for (int p = value.params.nextSetBit(0); p >= 0; p = value.params.nextSetBit(p + 1)) {
                markParamEscaped(p, destination, at);
            }
        }

        void markParamEscaped(int p, String destination, ParserRuleContext at) {
            if (borrowParams[p]) {
                borrowParams[p] = false;
            }
            ParamMods declared = parseParamMods(params.get(p), false);
            if (declared.borrow()) {
                reportOnce("borrow parameter " + ScopeAnalysis.paramId(params.get(p)).getText() + " escapes via " + destination, at);
            }
        }

        void recordReturnOrigin(Origin retOrigin, ParserRuleContext at) {
            if (retOrigin == Origin.IMMUTABLE) {
                return;
            }
            if (!retOrigin.unique || !retOrigin.params.isEmpty() || retOrigin.aliasesThis || retOrigin.external) {
                uniqueResult = false;
            } else if (at instanceof BabbleParser.ReturnExpressionContext
                    && retOrigin.uniqueOwner instanceof ParserRuleContext decl) {
                env.put(decl, Origin.MOVED);
            }
            markStoredOrigin(retOrigin, "return value", at);
        }

        private void checkClosureCaptureEscape(BabbleParser.FunctionLiteralContext inner) {
            boolean frameLocal = frameLocalOwner.get(inner) == owner;
            CallableSummary innerSum = summaries.get(inner);
            if (frameLocal && innerSum != null) {
                // Propagate frame-local helper closure's frame effects if it is invoked!
                // (Construction alone has no side effects.)
            }
            Resolution res = pass.analysis.resolution();
            if (res == null) {
                return;
            }
            // Check if inner closure captures `this` or any parameter of `owner`!
            boolean capturesThis = innerContainsThis(inner);
            if (capturesThis) {
                if (!frameLocal) {
                    markStoredOrigin(Origin.ofThis(THIS_KEY, false), "captured closure", inner);
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
                if (orig != null && !frameLocal) {
                    markStoredOrigin(orig, "captured closure", inner);
                }
            }
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
            int iter = 0;
            while (iter++ < 8) {
                Map<ParserRuleContext, Origin> before = new IdentityHashMap<>(env);
                eval(ctx.test);
                eval(ctx.whileBlock);
                Map<ParserRuleContext, Origin> merged = mergeEnvs(before, env);
                if (merged.equals(before)) {
                    env = merged;
                    break;
                }
                env = merged;
            }
            return Origin.IMMUTABLE;
        }

        private Origin evalBooleanOp(BabbleParser.BooleanOpContext ctx) {
            eval(ctx.left);
            Map<ParserRuleContext, Origin> before = new IdentityHashMap<>(env);
            eval(ctx.right);
            env = mergeEnvs(before, env);
            return Origin.IMMUTABLE;
        }

        private Origin evalBinaryOp(BabbleParser.BinaryOpContext bin) {
            eval(bin.left);
            eval(bin.right);
            String op = bin.op.getText();
            Type lt = effectiveType(bin.left);
            Type rt = effectiveType(bin.right);
            Type res = checker.rawTypeOf(bin);
            switch (op) {
                case "+" -> {
                    if (res == Type.STRING) {
                        if (lt != Type.STRING) {
                            applyEffects(toStringProfile(lt));
                        }
                        if (rt != Type.STRING) {
                            applyEffects(toStringProfile(rt));
                        }
                    }
                }
                case "==", "!=", "<>" -> applyEffects(equalityProfile(lt, rt));
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
            // Start optimistic for effects when specializing, keeping declared/inferred mut/borrow base.
            int n = paramDecls.size();
            boolean[] mp = base.mutParams().clone();
            boolean[] bp = new boolean[n];
            for (int i = 0; i < n; i++) {
                bp[i] = true;
            }
            EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
            CallableSummary start = new CallableSummary(
                    EffectSet.ofInferred(opt, base.effects().mutThis(), canReferenceThis(checker.scopeOf(targetLit))),
                    mp, bp);
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
        boolean hasExplicit = prefix.hasAnyModifier() || prefix.axiom() || prefix.impure();
        List<BabbleParser.FnParamContext> paramDecls = lit.fnParameters().fnParam();
        int n = paramDecls.size();
        if (hasExplicit || n == 0) {
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
                    base, false, base.effects(), base.mutParams(), base.borrowParams(),
                    new boolean[0], new EffectSet[0], new long[0][]);
        }

        EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
        EffectSet pureEff = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, false);
        CallableSummary start = new CallableSummary(
                EffectSet.ofInferred(opt, base.effects().mutThis(), canReferenceThis(checker.scopeOf(lit))),
                base.mutParams().clone(),
                allTrue(n));

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
