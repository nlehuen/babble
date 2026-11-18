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
 * exclusivity, flow-sensitive {@code nothrow}, and compositional algebraic checker (BIP007-P1).
 */
final class EffectChecker {

    private static final int MAX_SPECIALIZATION_DEPTH = 4;

    record ParsedPrefix(EnumSet<Effect> effects,
                        boolean mutThis,
                        boolean borrowThis,
                        boolean axiom,
                        boolean impure,
                        boolean hasAnyModifier) {
        static final ParsedPrefix EMPTY = new ParsedPrefix(
                EnumSet.noneOf(Effect.class), false, false, false, false, false);

        EffectSet toEffectSet() {
            if (impure) {
                return EffectSet.IMPURE_DECLARED;
            }
            return EffectSet.ofDeclared(effects, mutThis, borrowThis, axiom);
        }
    }

    record ParamMods(boolean mut, boolean borrow) {
        static final ParamMods NONE = new ParamMods(false, false);
    }

    record CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams,
                           Set<BabbleParser.FunctionLiteralContext> frameReadOwners,
                           Set<BabbleParser.FunctionLiteralContext> frameWriteOwners) {
        CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams) {
            this(effects, mutParams, borrowParams, Set.of(), Set.of());
        }
    }

    record JavaCallProfile(EffectSet effects, boolean mutReceiver, boolean borrowReceiver,
                           boolean[] mutParams, boolean[] borrowParams, boolean explicitNothrow) {
    }

    /** Tracks alias provenance and direct binding identity for static mut/borrow/exclusivity checks. */
    private static final class Origin {
        static final Origin IMMUTABLE = new Origin(new BitSet(0), false, false, Set.of(), null);

        final BitSet params;
        final boolean aliasesThis;
        final boolean external;
        /** Direct local/param/this identity keys when an expression is a direct alias (e.g. {@code def y = x}). */
        final Set<Object> directKeys;
        /** Pure member path key when an expression is {@code base.f1.f2} without calls. */
        final PathKey pathKey;

        Origin(BitSet params, boolean aliasesThis, boolean external, Set<Object> directKeys, PathKey pathKey) {
            this.params = params;
            this.aliasesThis = aliasesThis;
            this.external = external;
            this.directKeys = directKeys;
            this.pathKey = pathKey;
        }

        static Origin ofParam(int index, Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            BitSet bits = new BitSet();
            bits.set(index);
            return new Origin(bits, false, false, Set.of(key), new PathKey(key, List.of()));
        }

        static Origin ofThis(Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            return new Origin(new BitSet(0), true, false, Set.of(key), new PathKey(key, List.of()));
        }

        static Origin ofExternal(Object key, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            Set<Object> keys = key != null ? Set.of(key) : Set.of();
            PathKey pk = key != null ? new PathKey(key, List.of()) : null;
            return new Origin(new BitSet(0), false, true, keys, pk);
        }

        boolean isEmpty() {
            return params.isEmpty() && !aliasesThis && !external;
        }

        Origin deriveMember(String field, boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            PathKey nextPath = pathKey != null ? pathKey.append(field) : null;
            return new Origin((BitSet) params.clone(), aliasesThis, external, Set.of(), nextPath);
        }

        Origin stripDirectIdentity(boolean immutable) {
            if (immutable) {
                return IMMUTABLE;
            }
            return new Origin((BitSet) params.clone(), aliasesThis, external, Set.of(), null);
        }

        Origin join(Origin other) {
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
            return new Origin(merged, aliasesThis || other.aliasesThis, external || other.external, keys, pk);
        }

        private Origin withoutExactSingleKey() {
            return new Origin((BitSet) params.clone(), aliasesThis, external, directKeys, pathKey);
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
                    && params.equals(that.params)
                    && directKeys.equals(that.directKeys)
                    && Objects.equals(pathKey, that.pathKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(params, aliasesThis, external, directKeys, pathKey);
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
    private final TypeChecker checker;

    /** File, session, and imported-module Java target overrides (tier 1 / tier 4). */
    private final Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> localJavaEffects = new LinkedHashMap<>();
    /** Top-level Java overrides committed when this input belongs to a REPL session. */
    private final Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> committedJavaEffects = new LinkedHashMap<>();
    /** Exported Java overrides published on {@link ModuleInterface}. */
    private final Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects = new LinkedHashMap<>();
    /** Defs that carry {@code = axiom ...} or {@code = impure ...} on non-function-literal values. */
    private final Map<BabbleParser.DefExpressionContext, ParsedPrefix> defPrefixes = new IdentityHashMap<>();

    private final Map<BabbleParser.FunctionLiteralContext, ParsedPrefix> fnPrefixes = new IdentityHashMap<>();
    private final Map<BabbleParser.ParameterDeclarationContext, ParamMods> paramModsMap = new IdentityHashMap<>();
    private final Set<ParseTree> validatedTypeTrees = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Closures proven not to escape their enclosing function {@code F} (only called or passed to borrow slots). */
    private final Map<BabbleParser.FunctionLiteralContext, BabbleParser.FunctionLiteralContext> frameLocalOwner = new IdentityHashMap<>();
    /** Local defs of {@code F} captured by an *escaping* closure (cannot be treated as private frame state). */
    private final Set<ParserRuleContext> escapedCapturedLocals = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Converged internal summaries for all callables (FunctionLiteralContext and ClassExpressionContext). */
    private final Map<ParserRuleContext, CallableSummary> summaries = new IdentityHashMap<>();
    /** Caller-visible summaries (locked to declared contract for annotated callables per BIP007-D2). */
    private final Map<ParserRuleContext, CallableSummary> visibleSummaries = new IdentityHashMap<>();

    /** Active call-site specialization stack to bound recursive higher-order specialization. */
    private final Deque<SpecKey> activeSpecializations = new ArrayDeque<>();
    private final Map<SpecKey, CallableSummary> specializationCache = new HashMap<>();

    private record SpecKey(BabbleParser.FunctionLiteralContext fn, List<Object> callbackBindings) {
    }

    EffectChecker(BlockScopes pass, TypeChecker checker) {
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
                ctx.effectPrefix() != null ? ctx.effectPrefix().effectWord().stream().map(w -> w.ID().getSymbol()).toList() : List.of(),
                false, canReferenceThis(checker.scopeOf(ctx)), ctx, reportErrors);
        // Check pure + mut parameter conflict.
        boolean hasMutParam = false;
        boolean hasAnyParamMod = false;
        for (BabbleParser.ParameterDeclarationContext param : ctx.parametersDeclaration().parameterDeclaration()) {
            ParamMods pm = parseParamMods(param, reportErrors);
            hasMutParam |= pm.mut();
            hasAnyParamMod |= pm.mut() || pm.borrow();
        }
        if (reportErrors) {
            if (parsed.effects().contains(Effect.PURE) && hasMutParam) {
                checker.effectDiagnostic("pure and mut are mutually exclusive : use hermetic with mut parameters", ctx);
            }
            if (parsed.axiom() && parsed.effects().isEmpty() && !parsed.mutThis() && !parsed.borrowThis() && !hasAnyParamMod) {
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
            parsed = new ParsedPrefix(fixed, parsed.mutThis(), parsed.borrowThis(), parsed.axiom(), parsed.impure(), true);
        }
        if (checker.inFile(ctx)) {
            fnPrefixes.put(ctx, parsed);
        }
        return parsed;
    }

    ParsedPrefix parseFunctionTypePrefix(BabbleParser.FunctionTypeContext ctx, boolean reportErrors) {
        boolean report = reportErrors && validatedTypeTrees.add(ctx);
        List<Token> words = ctx.effectPrefix() != null
                ? ctx.effectPrefix().effectWord().stream().map(w -> w.ID().getSymbol()).toList()
                : List.of();
        ParsedPrefix parsed = parseWords(words, true, true, ctx, report);
        boolean hasMutParam = false;
        for (BabbleParser.ParameterDeclarationContext param : ctx.parametersDeclaration().parameterDeclaration()) {
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
            return new ParsedPrefix(fixed, parsed.mutThis(), parsed.borrowThis(), false, false, true);
        }
        return parsed;
    }

    ParamMods parseParamMods(BabbleParser.ParameterDeclarationContext param, boolean reportErrors) {
        if (!reportErrors) {
            ParamMods cached = paramModsMap.get(param);
            if (cached != null) {
                return cached;
            }
        }
        boolean mut = false;
        boolean borrow = false;
        for (BabbleParser.ParamModifierContext modCtx : param.paramModifier()) {
            Token tok = modCtx.ID().getSymbol();
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
                default -> {
                    if (reportErrors) {
                        checker.effectDiagnostic("Unknown parameter modifier : " + tok.getText(), modCtx);
                    }
                }
            }
        }
        ParamMods mods = (mut || borrow) ? new ParamMods(mut, borrow) : ParamMods.NONE;
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
            } else if (impure && (!effects.isEmpty() || mutThis || borrowThis)) {
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
        return new ParsedPrefix(effects, mutThis, borrowThis, axiom, impure, true);
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
        }
    }

    CallableSummary visibleSummary(BabbleParser.FunctionLiteralContext fn) {
        return visibleSummaries.get(fn);
    }

    CallableSummary constructorSummary(TypeNode node) {
        if (node.ctx instanceof BabbleParser.ClassExpressionContext cls) {
            CallableSummary local = visibleSummaries.get(cls);
            if (local != null) {
                return local;
            }
            TypeChecker owner = checker.ownerOf(cls);
            if (owner != null && owner != checker && owner.effectChecker != null) {
                return owner.effectChecker.visibleSummaries.get(cls);
            }
        }
        return null;
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
            List<Token> words = ctx.targetEffectPrefix().targetEffectWord().stream().map(w -> w.ID().getSymbol()).toList();
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

        // 2. DefExpressions with targetEffectPrefix (e.g. def mkdirs = axiom idempotent Files.createDirectories).
        collectDefAxioms(file);
    }

    private void collectDefAxioms(ParseTree tree) {
        if (tree instanceof BabbleParser.DefExpressionContext def && def.targetEffectPrefix() != null) {
            List<Token> words = def.targetEffectPrefix().targetEffectWord().stream().map(w -> w.ID().getSymbol()).toList();
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
        List<org.antlr.v4.runtime.tree.TerminalNode> ids = targetCtx.ID();
        int idx = 0;
        Type current;
        if (targetCtx.STRING() != null) {
            String first = ids.get(0).getText();
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
            idx = 1;
        } else {
            String first = ids.get(0).getText();
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
            idx = 1;
        }

        while (idx < ids.size()) {
            String step = ids.get(idx).getText();
            boolean last = (idx == ids.size() - 1);
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
            } else if (current instanceof Type.Package pkg) {
                current = checker.packageMemberTypeForEffects(pkg, step);
                if (current == null) {
                    checker.effectDiagnostic("Unknown member : " + step + " in package " + pkg.name(), reportAt);
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
            idx++;
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
        return new HostEffects.OverrideEntry(prefix.effects(), prefix.mutThis(), prefix.borrowThis(), false);
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
        // Tier 7: Unconstrained default.
        int paramCount = exactCtor != null ? exactCtor.getParameterCount() : 0;
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
        if (exec != null) {
            Parameter[] params = exec.getParameters();
            for (int i = 0; i < params.length; i++) {
                if (params[i].isAnnotationPresent(Mut.class) && params[i].getType() != String.class) {
                    mutParams[i] = true;
                }
                if (params[i].isAnnotationPresent(Borrow.class)) {
                    borrowParams[i] = true;
                }
            }
        }
        EffectSet set = EffectSet.ofDeclared(entry.effects(), mutReceiver, !isStaticOrCtor && borrowAll, true);
        return new JavaCallProfile(set, mutReceiver, !isStaticOrCtor && borrowAll, mutParams, borrowParams, explicitNothrow);
    }

    private static boolean hasAnyEffectAnnotation(Executable exec) {
        if (hasElementEffectAnnotation(exec)) {
            return true;
        }
        for (Parameter p : exec.getParameters()) {
            if (p.isAnnotationPresent(Mut.class) || p.isAnnotationPresent(Borrow.class)) {
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
        AnnotatedElement primary = (!classLevelOnly && exec != null) ? exec : classSource;
        if (primary.isAnnotationPresent(Impure.class)) {
            return new JavaCallProfile(EffectSet.IMPURE_DECLARED, false, false, mutParams, borrowParams, false);
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
        if (exec != null) {
            Parameter[] params = exec.getParameters();
            for (int i = 0; i < params.length; i++) {
                if (params[i].isAnnotationPresent(Mut.class) && params[i].getType() != String.class) {
                    mutParams[i] = true;
                }
                if (params[i].isAnnotationPresent(Borrow.class) || (exec instanceof Constructor<?> && borrowReceiver)) {
                    borrowParams[i] = true;
                }
            }
        }
        if (mutReceiver || anyTrue(mutParams)) {
            effects.remove(Effect.PURE);
        }
        boolean explicitNothrow = effects.contains(Effect.NOTHROW);
        EffectSet set = EffectSet.ofDeclared(effects, mutReceiver, !isStaticOrCtor && borrowReceiver, true);
        return new JavaCallProfile(set, mutReceiver, !isStaticOrCtor && borrowReceiver, mutParams, borrowParams, explicitNothrow);
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

    /** Tier 6 : Standard value protocol defaults (bip007_effects.md §4.3, §6.2). */
    private static JavaCallProfile standardProtocolDefault(Class<?> owner, String methodName, Method exactMethod) {
        int paramCount = exactMethod != null ? exactMethod.getParameterCount() : -1;
        if ("equals".equals(methodName) && (paramCount == -1 || paramCount == 1)) {
            EffectSet set = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, true);
            return new JavaCallProfile(set, false, true, new boolean[]{false}, new boolean[]{true}, false);
        }
        if ("compareTo".equals(methodName) && (paramCount == -1 || paramCount == 1)
                && (owner != null && Comparable.class.isAssignableFrom(owner))) {
            EffectSet set = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, true);
            return new JavaCallProfile(set, false, true, new boolean[]{false}, new boolean[]{true}, false);
        }
        if (("hashCode".equals(methodName) || "toString".equals(methodName)) && (paramCount == -1 || paramCount == 0)) {
            Class<?> decl = resolveDeclaringClass(owner, methodName);
            boolean deterministic = decl != null && decl != Object.class && !decl.isInterface();
            EnumSet<Effect> effs = deterministic
                    ? EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC)
                    : EnumSet.of(Effect.PURE, Effect.HERMETIC);
            EffectSet set = EffectSet.ofInferred(effs, false, true);
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
        if (profile.effects().isUnconstrained() && !anyTrue(profile.mutParams()) && !anyTrue(profile.borrowParams())) {
            return rawSam;
        }
        // Only attach explicit annotations/overrides on the SAM interface method itself (not Object defaults).
        if (!profile.effects().hasExplicitAnnotations() && !anyTrue(profile.mutParams()) && !anyTrue(profile.borrowParams())) {
            return rawSam;
        }
        List<Type.Parameter> updated = new ArrayList<>(rawSam.parameters().size());
        for (int i = 0; i < rawSam.parameters().size(); i++) {
            Type.Parameter p = rawSam.parameters().get(i);
            boolean mut = i < profile.mutParams().length && profile.mutParams()[i];
            boolean borrow = i < profile.borrowParams().length && profile.borrowParams()[i];
            updated.add(new Type.Parameter(p.name(), p.type(), p.optional(), mut, borrow, borrow));
        }
        EffectSet fnEffects = new EffectSet(profile.effects().effects(), profile.effects().effects(),
                false, false, false, false, false);
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
                        && defScope.kind != ScopeNode.Kind.PACKAGE
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
        List<BabbleParser.ParameterDeclarationContext> params = lit.parametersDeclaration().parameterDeclaration();
        if (paramIdx < 0 || paramIdx >= params.size()) {
            return false;
        }
        BabbleParser.ParameterDeclarationContext targetParam = params.get(paramIdx);
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
        for (BabbleParser.ParameterDeclarationContext param : fn.parametersDeclaration().parameterDeclaration()) {
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
            List<BabbleParser.ParameterDeclarationContext> params = fn.parametersDeclaration().parameterDeclaration();
            int n = params.size();
            boolean[] mutParams = new boolean[n];
            boolean[] borrowParams = new boolean[n];
            for (int i = 0; i < n; i++) {
                ParamMods pm = parseParamMods(params.get(i), true);
                mutParams[i] = pm.mut();
                borrowParams[i] = pm.borrow();
            }
            if (prefix.impure()) {
                return new CallableSummary(EffectSet.IMPURE_DECLARED, mutParams, new boolean[n]);
            }
            if (prefix.axiom()) {
                EffectSet set = prefix.toEffectSet();
                return new CallableSummary(set, mutParams, borrowParams);
            }
            // Optimistic start for inference/verification: {HERMETIC, DETERMINISTIC, NOTHROW}, borrow all!
            for (int i = 0; i < n; i++) {
                borrowParams[i] = true;
            }
            EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
            boolean borrowThis = canReferenceThis(checker.scopeOf(fn));
            EffectSet set = new EffectSet(opt, prefix.effects(), prefix.mutThis(), borrowThis, prefix.borrowThis(), false, false);
            return new CallableSummary(set, mutParams, borrowParams);
        }
        // ClassExpressionContext constructor:
        BabbleParser.ClassExpressionContext cls = (BabbleParser.ClassExpressionContext) callable;
        List<BabbleParser.ParameterDeclarationContext> params = cls.parametersDeclaration() != null
                ? cls.parametersDeclaration().parameterDeclaration() : List.of();
        int n = params.size();
        boolean[] mutParams = new boolean[n];
        boolean[] borrowParams = new boolean[n];
        TypeNode node = pass.typeNodes.get(cls);
        boolean isValue = node != null && node.kind == ClassShape.Kind.VALUE_CLASS;
        for (int i = 0; i < n; i++) {
            ParamMods pm = parseParamMods(params.get(i), true);
            if (isValue && pm.mut()) {
                checker.effectDiagnostic("value class constructor parameter cannot be mut : " + params.get(i).ID().getText(), params.get(i));
            }
            mutParams[i] = !isValue && pm.mut();
            // Field parameters (`def` or `value class`) are stored in `this`, so non-immutable ones escape into the new instance!
            boolean storedAsField = isValue || params.get(i).DEF() != null;
            Type pType = checker.typeOfDeclarationForEffects(params.get(i));
            borrowParams[i] = !storedAsField || isDeeplyImmutable(pType);
        }
        EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
        return new CallableSummary(EffectSet.ofInferred(opt, false, false), mutParams, borrowParams);
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
                    TypeNode.Member mem = inst.node().members.get(sel.ID().getText());
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
        List<BabbleParser.ParameterDeclarationContext> params = fn.parametersDeclaration().parameterDeclaration();
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
                mutThis,
                borrowThis,
                prefix,
                activeFrameChain(fn),
                specializedParams,
                emitDiagnostics);

        // Parameter defaults execute when omitted at call sites.
        for (BabbleParser.ParameterDeclarationContext p : params) {
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
                false,
                false);
        return new CallableSummary(nextSet, analyzer.mutParams, analyzer.borrowParams,
                Set.copyOf(analyzer.frameReadOwners), Set.copyOf(analyzer.frameWriteOwners));
    }

    private CallableSummary analyzeConstructorBody(BabbleParser.ClassExpressionContext cls,
                                                   CallableSummary current,
                                                   boolean emitDiagnostics) {
        TypeNode node = pass.typeNodes.get(cls);
        boolean isValue = node != null && node.kind == ClassShape.Kind.VALUE_CLASS;
        List<BabbleParser.ParameterDeclarationContext> params = cls.parametersDeclaration() != null
                ? cls.parametersDeclaration().parameterDeclaration() : List.of();
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
                true, // constructor initializes its own fresh `this` fields!
                false,
                pseudoPrefix,
                Set.of(),
                null,
                emitDiagnostics);
        analyzer.constructingSelf = true;

        for (int i = 0; i < params.size(); i++) {
            BabbleParser.ParameterDeclarationContext p = params.get(i);
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
        EffectSet nextSet = EffectSet.ofInferred(nextEffects, false, false);
        return new CallableSummary(nextSet, analyzer.mutParams, analyzer.borrowParams);
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
                    && cls.parametersDeclaration() != null
                    && !cls.parametersDeclaration().parameterDeclaration().isEmpty()
                    && cls.parametersDeclaration().parameterDeclaration().get(0).defaultValue == null) {
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
                false,
                false);
        return new CallableSummary(finalized, raw.mutParams(), raw.borrowParams(),
                raw.frameReadOwners(), raw.frameWriteOwners());
    }

    private CallableSummary buildVisibleSummary(ParserRuleContext callable, CallableSummary internal) {
        if (!(callable instanceof BabbleParser.FunctionLiteralContext fn)) {
            return internal;
        }
        ParsedPrefix prefix = parseFunctionPrefix(fn, false);
        List<BabbleParser.ParameterDeclarationContext> params = fn.parametersDeclaration().parameterDeclaration();
        boolean hasAnyExplicit = prefix.hasAnyModifier();
        for (BabbleParser.ParameterDeclarationContext p : params) {
            ParamMods pm = parseParamMods(p, false);
            hasAnyExplicit |= pm.mut() || pm.borrow();
        }
        if (!hasAnyExplicit) {
            // Unannotated function (including unannotated `export def` per BIP007-D2):
            // expose full inferred EffectSet, inferred borrow, and (for untyped fns) inferred mut!
            return internal;
        }
        if (prefix.axiom() || prefix.impure()) {
            return internal;
        }
        // Annotated function: lock caller-visible contract to declared modifiers (BIP007-D2, §5.1.5)!
        boolean[] declMut = new boolean[params.size()];
        boolean[] declBorrow = new boolean[params.size()];
        for (int i = 0; i < params.size(); i++) {
            ParamMods pm = parseParamMods(params.get(i), false);
            declMut[i] = pm.mut();
            declBorrow[i] = pm.borrow();
        }
        EffectSet declSet = prefix.toEffectSet();
        return new CallableSummary(declSet, declMut, declBorrow);
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
        // Run BodyAnalyzer in unconstrained top-level mode to enforce mut exclusivity across top-level calls!
        BodyAnalyzer top = new BodyAnalyzer(
                file, pass.fileScope, List.of(), false,
                new boolean[0], new boolean[0], false, false,
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
                new boolean[0], new boolean[0], false, false,
                req, Set.of(), null, true);
        ba.evalBlock(block, false);
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
                    new boolean[0], new boolean[0], false, false,
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
            pass.analysis.setCallEffects(call, EffectSet.ofInferred(effs, false, false));
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
        List<BabbleParser.ParameterDeclarationContext> paramDecls = fn.parametersDeclaration().parameterDeclaration();
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
                BabbleParser.ParameterDeclarationContext p0 = paramDecls.get(0);
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
                BabbleParser.ParameterDeclarationContext p0 = paramDecls.get(0);
                BabbleParser.ParameterDeclarationContext p1 = paramDecls.get(1);
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

    private RelProps relationalProps(BabbleParser.ExpressionContext raw, BabbleParser.ParameterDeclarationContext targetParam) {
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
                                            BabbleParser.ParameterDeclarationContext p0,
                                            BabbleParser.ParameterDeclarationContext p1) {
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
            if (args.size() != 2 || args.get(0).ID() != null || args.get(1).ID() != null) {
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
                                  BabbleParser.ParameterDeclarationContext p0,
                                  BabbleParser.ParameterDeclarationContext p1) {
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
                                             BabbleParser.ParameterDeclarationContext p0) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        if (resolvePlainParam(expr) == p0) {
            // Identity function `(x) -> (x)` is idempotent!
            return true;
        }
        if (expr instanceof BabbleParser.CallContext call) {
            List<BabbleParser.CallParameterContext> args = call.callParameters().callParameter();
            if (args.size() == 1 && args.get(0).ID() == null && resolvePlainParam(unwrapParens(args.get(0).expression())) == p0) {
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
                                        List<BabbleParser.ParameterDeclarationContext> params,
                                        boolean[] mutParams,
                                        boolean mutThis) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        // 1. Direct overwrite `this.field = val` or `mutParam.field = val` where `val` does not read the overwritten field!
        if (expr instanceof BabbleParser.AssignExpressionContext assign && "=".equals(assign.op.getText()) && assign.namespace != null) {
            BabbleParser.ExpressionContext ns = unwrapParens(assign.namespace);
            if ((mutThis && ns instanceof BabbleParser.ThisExpressionContext)
                    || (resolvePlainParam(ns) instanceof BabbleParser.ParameterDeclarationContext p
                    && params.indexOf(p) >= 0 && mutParams[params.indexOf(p)])) {
                if (!readsField(assign.value, assign.name.getText())) {
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
                                         List<BabbleParser.ParameterDeclarationContext> params,
                                         boolean[] mutParams,
                                         boolean mutThis) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        // `this.x += delta` on int (`+` is associative & commutative!) or forwarding to a `mut commutative` method!
        if (expr instanceof BabbleParser.AssignExpressionContext assign && assign.namespace != null) {
            String op = assign.op.getText();
            Type targetType = checker.rawTypeOf(assign);
            if ("+=".equals(op) && targetType == Type.INT && params.size() == 1
                    && resolvePlainParam(unwrapParens(assign.value)) == params.get(0)) {
                return true;
            }
            if ("=".equals(op) && targetType == Type.INT && params.size() == 1
                    && assign.value instanceof BabbleParser.BinaryOpContext bin && "+".equals(bin.op.getText())) {
                if ((sameTargetField(assign.namespace, assign.name.getText(), bin.left)
                        && resolvePlainParam(unwrapParens(bin.right)) == params.get(0))
                        || (sameTargetField(assign.namespace, assign.name.getText(), bin.right)
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
        if (!(expr instanceof BabbleParser.SelectorContext sel) || sel.expression() == null || !field.equals(sel.ID().getText())) {
            return false;
        }
        BabbleParser.ExpressionContext r1 = unwrapParens(ns);
        BabbleParser.ExpressionContext r2 = unwrapParens(sel.expression());
        return r1 instanceof BabbleParser.ThisExpressionContext && r2 instanceof BabbleParser.ThisExpressionContext;
    }

    private static boolean readsField(ParseTree tree, String fieldName) {
        if (tree instanceof BabbleParser.SelectorContext sel && sel.expression() != null
                && fieldName.equals(sel.ID().getText())) {
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
            if (r instanceof BabbleParser.ParameterDeclarationContext) {
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
        if (type == Type.INT || type == Type.DOUBLE || type == Type.BOOL || type == Type.STRING || type == Type.NULL) {
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
                if (m.function == null) {
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

    private boolean isImmutableConstantValue(Type type) {
        if (isDeeplyImmutable(type)) {
            return true;
        }
        if (type instanceof Type.Function || type instanceof Type.ClassValue
                || type instanceof Type.JavaClassValue || type instanceof Type.JavaPackage
                || type instanceof Type.JavaMethod || type instanceof Type.Module || type instanceof Type.Package) {
            return true;
        }
        return false;
    }

    /** Profile of converting {@code type} to string in {@code string + x} (bip007_effects.md §4.3, §4.6). */
    private EffectSet toStringProfile(Type type) {
        if (type == null || type == Type.ERROR) {
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true);
        }
        if (type == Type.INT || type == Type.DOUBLE || type == Type.BOOL || type == Type.STRING || type == Type.NULL) {
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true);
        }
        if (type instanceof Type.Union u) {
            EffectSet acc = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true);
            for (Type m : u.members()) {
                acc = acc.intersect(toStringProfile(m));
            }
            return acc;
        }
        if (type instanceof Type.Instance inst) {
            TypeNode node = inst.node();
            TypeNode.Member custom = checker.findMemberOnNode(node, "toString");
            if (custom != null && custom.function != null) {
                CallableSummary cs = summaries.get(custom.function);
                if (cs != null) {
                    return cs.effects();
                }
                Type mt = checker.memberTypeForEffects(custom);
                if (mt instanceof Type.Function tf) {
                    return tf.effects();
                }
            }
            if (node.kind == ClassShape.Kind.VALUE_CLASS) {
                // Structural toString formats every field!
                EffectSet acc = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true);
                for (TypeNode.Member m : node.members.values()) {
                    if (m.function == null) {
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
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.NOTHROW), false, true);
        }
        if (type instanceof Type.JavaInstance ji) {
            return resolveJavaMethodProfile(ji.type(), "toString", null).effects();
        }
        if (type instanceof Type.JavaArray) {
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.NOTHROW), false, true);
        }
        return EffectSet.EMPTY;
    }

    private EffectSet equalityProfile(Type left, Type right) {
        return singleEqualityProfile(left).intersect(singleEqualityProfile(right));
    }

    private EffectSet singleEqualityProfile(Type type) {
        if (type == null || type == Type.ERROR || type == Type.INT || type == Type.DOUBLE
                || type == Type.BOOL || type == Type.STRING || type == Type.NULL
                || type instanceof Type.Function || type instanceof Type.ClassValue
                || type instanceof Type.JavaClassValue) {
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true);
        }
        if (type instanceof Type.Union u) {
            EffectSet acc = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true);
            for (Type m : u.members()) {
                acc = acc.intersect(singleEqualityProfile(m));
            }
            return acc;
        }
        if (type instanceof Type.Instance inst) {
            if (inst.node().kind == ClassShape.Kind.VALUE_CLASS) {
                EffectSet acc = EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true);
                for (TypeNode.Member m : inst.node().members.values()) {
                    if (m.function == null) {
                        Type mt = checker.instanceMemberTypeForEffects(inst, m);
                        if (mt != null) {
                            acc = acc.intersect(singleEqualityProfile(mt));
                        }
                    }
                }
                return acc;
            }
            // Regular BabbleInstance uses `==` reference identity (pure deterministic nothrow)!
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true);
        }
        if (type instanceof Type.JavaInstance ji) {
            return resolveJavaMethodProfile(ji.type(), "equals", null).effects();
        }
        return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, true);
    }

    private EffectSet comparisonProfile(Type left, Type right) {
        if (isPrimitiveOrString(left) && isPrimitiveOrString(right)) {
            return EffectSet.ofInferred(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true);
        }
        if (left instanceof Type.JavaInstance ji) {
            return resolveJavaMethodProfile(ji.type(), "compareTo", null).effects();
        }
        return EffectSet.EMPTY;
    }

    private static boolean isPrimitiveOrString(Type t) {
        return t == Type.INT || t == Type.DOUBLE || t == Type.BOOL || t == Type.STRING;
    }

    // ================================================================ 11. Intra-Procedural Flow & Alias Analyzer

    private final class BodyAnalyzer {
        private final ParserRuleContext owner;
        private final ScopeNode ownerScope;
        private final List<BabbleParser.ParameterDeclarationContext> params;
        private final Map<ParserRuleContext, Integer> paramIndex = new IdentityHashMap<>();
        private final boolean inferMut;
        final boolean[] mutParams;
        final boolean[] borrowParams;
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

        final Set<BabbleParser.FunctionLiteralContext> frameReadOwners = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<BabbleParser.FunctionLiteralContext> frameWriteOwners = Collections.newSetFromMap(new IdentityHashMap<>());

        private Map<ParserRuleContext, Origin> env = new IdentityHashMap<>();
        private final Deque<Map<ParserRuleContext, Type>> narrowedScopes = new ArrayDeque<>();
        private final Set<String> reportedMessages = new HashSet<>();

        BodyAnalyzer(ParserRuleContext owner,
                     ScopeNode ownerScope,
                     List<BabbleParser.ParameterDeclarationContext> params,
                     boolean inferMut,
                     boolean[] mutParams,
                     boolean[] borrowParams,
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
            this.mutThis = mutThis;
            this.borrowThis = borrowThis;
            this.declaredPrefix = declaredPrefix;
            this.frameChain = frameChain;
            this.specializedParams = specializedParams;
            this.emitDiagnostics = emitDiagnostics;

            for (int i = 0; i < params.size(); i++) {
                BabbleParser.ParameterDeclarationContext p = params.get(i);
                paramIndex.put(p, i);
                Type pType = checker.typeOfDeclarationForEffects(p);
                env.put(p, Origin.ofParam(i, p, isDeeplyImmutable(pType)));
            }
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
            if (block == null) {
                return Origin.IMMUTABLE;
            }
            Origin last = Origin.IMMUTABLE;
            for (BabbleParser.ExpressionContext stmt : block.expression()) {
                last = eval(stmt);
            }
            return last;
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
                eval(stmt);
            }
        }

        Origin eval(ParserRuleContext ctx) {
            if (ctx == null) {
                return Origin.IMMUTABLE;
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
                for (BabbleParser.ExpressionContext s : obj.createBlock.expression()) {
                    if (!(s instanceof BabbleParser.DefExpressionContext def && def.value instanceof BabbleParser.FunctionLiteralContext)) {
                        eval(s);
                    }
                }
                return Origin.ofExternal(obj, isDeeplyImmutable(checker.rawTypeOf(obj)));
            }
            if (ctx instanceof BabbleParser.PackageExpressionContext pkg) {
                return evalBlock(pkg.packageBlock, false);
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
                eval(isCtx.right);
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
                markStoredOrigin(init, "field " + def.name.getText(), def);
            } else if (isOwnedScopeLocal(def, defScope, kind)) {
                // Local variable in this function (or top-level binding when verifying top-level file/static block)!
                Origin localOrig = isDeeplyImmutable(defType) ? Origin.IMMUTABLE : withLocalAliasKey(init, def, defType);
                env.put(def, localOrig);
            } else {
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
                        && declScope.kind != ScopeNode.Kind.FILE
                        && declScope.kind != ScopeNode.Kind.PACKAGE;
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
            String compoundOp = Operators.compound(assign.op.getText());
            if (assign.namespace == null) {
                Origin rhs = eval(assign.value);
                if (compoundOp != null) {
                    checkCompoundArithmeticEffects(compoundOp, checker.rawTypeOf(assign), assign);
                }
                ScopeNode occScope = checker.occurrenceScopeOf(assign);
                Object resolved = checker.resolveNameForEffects(assign.name.getText(), occScope);
                if (resolved instanceof ParserRuleContext decl) {
                    if (paramIndex.containsKey(decl)) {
                        // Local parameter variable reassignment (rebinds local slot, does NOT mutate caller's object!).
                        Type pType = checker.typeOfDeclarationForEffects(decl);
                        Origin merged = compoundOp != null ? Origin.IMMUTABLE : withLocalAliasKey(rhs, decl, pType);
                        env.put(decl, merged);
                        return merged;
                    }
                    BabbleParser.FunctionLiteralContext declFn = enclosingFunctionOfDecl(decl);
                    ScopeNode declScope = pass.definitionScopes.get(decl);
                    ScopeAnalysis.DefKind dKind = decl instanceof BabbleParser.DefExpressionContext d
                            ? pass.analysis.defTarget(d).kind() : null;
                    if (isOwnedScopeLocal(decl, declScope, dKind)) {
                        Type dType = checker.typeOfDeclarationForEffects(decl);
                        Origin next = compoundOp != null ? Origin.IMMUTABLE : withLocalAliasKey(rhs, decl, dType);
                        env.put(decl, next);
                        return next;
                    }
                    if (declFn != null && frameChain.contains(declFn) && !escapedCapturedLocals.contains(decl)) {
                        // Frame-local (`borrow`) helper closure reassigning enclosing F's unescaped local `def` (§4.5)!
                        frameWriteOwners.add(declFn);
                        markStoredOrigin(rhs, "outer variable " + assign.name.getText(), assign);
                        return rhs;
                    }
                    // Outer / package / file / static variable write -> breaks hermetic/pure!
                    hermetic = false;
                    markStoredOrigin(rhs, "outer variable " + assign.name.getText(), assign);
                    return rhs;
                }
                hermetic = false;
                return rhs;
            }

            // Member assignment: `receiver.field = value` (or compound `+=`, etc.)
            Origin recvOrig = eval(assign.namespace);
            Origin rhs = eval(assign.value);
            if (compoundOp != null) {
                checkCompoundArithmeticEffects(compoundOp, checker.rawTypeOf(assign), assign);
            }
            Type recvType = effectiveType(assign.namespace);
            if (recvType == Type.ERROR || recvType == Type.ANY || Type.containsNull(recvType)
                    || isPrimitiveOrString(recvType) || recvType instanceof Type.Function) {
                return rhs;
            }
            if (recvType instanceof Type.JavaClassValue || recvType instanceof Type.ClassValue
                    || recvType instanceof Type.Package || recvType instanceof Type.Module) {
                // Static / package field write -> global mutation!
                hermetic = false;
                markStoredOrigin(rhs, "static member " + assign.name.getText(), assign);
                return rhs;
            }
            recordTargetMutation(recvOrig, assign.namespace, "field assignment ." + assign.name.getText(), assign);
            markStoredIntoTarget(recvOrig, rhs, assign.name.getText(), assign);
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
                    Type declType = effectiveDeclType(decl);
                    boolean imm = isDeeplyImmutable(declType);
                    if (paramIndex.containsKey(decl)) {
                        return env.getOrDefault(decl, Origin.ofParam(paramIndex.get(decl), decl, imm));
                    }
                    BabbleParser.FunctionLiteralContext declFn = enclosingFunctionOfDecl(decl);
                    ScopeNode declScope = pass.definitionScopes.get(decl);
                    ScopeAnalysis.DefKind dKind = decl instanceof BabbleParser.DefExpressionContext d
                            ? pass.analysis.defTarget(d).kind() : null;
                    if (isOwnedScopeLocal(decl, declScope, dKind)) {
                        if (escapedCapturedLocals.contains(decl) && checker.isReassigned(decl)) {
                            deterministic = false;
                        }
                        return env.getOrDefault(decl, Origin.ofExternal(decl, imm));
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
                return Origin.IMMUTABLE;
            }

            // Member read `receiver.field` or `receiver?.field`:
            Origin recvOrig = eval(sel.expression());
            Type recvType = effectiveType(sel.expression());
            Type resType = checker.rawTypeOf(sel);
            boolean immRes = isDeeplyImmutable(resType);
            String fieldName = sel.ID().getText();

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
            if (recvType instanceof Type.Package || recvType instanceof Type.Module) {
                Object memberDecl = checker.resolveNamespaceMemberDecl(recvType, fieldName);
                if (memberDecl instanceof ParserRuleContext decl && (checker.isReassigned(decl) || !isImmutableOuterBinding(decl, resType))) {
                    deterministic = false;
                }
                return Origin.ofExternal(memberDecl, immRes);
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
            List<BabbleParser.CallParameterContext> args = call.callParameters().callParameter();
            BabbleParser.ExpressionContext calleeExpr = call.expression();

            // Evaluate receiver / callee expression and arguments!
            Origin recvOrig = Origin.IMMUTABLE;
            BabbleParser.ExpressionContext receiverExpr = null;
            if (calleeExpr instanceof BabbleParser.SelectorContext sel && sel.expression() != null) {
                receiverExpr = sel.expression();
                Type recvType = effectiveType(receiverExpr);
                if (!(recvType instanceof Type.JavaPackage || recvType instanceof Type.Package || recvType instanceof Type.Module)) {
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
            }

            Type resType = checker.rawTypeOf(call);
            Type calleeType = resolveCalleeType(calleeExpr);

            // 1. Built-in `print` / `println` / `import`
            if (calleeType == Type.PRINT) {
                hermetic = false;
                deterministic = false;
                nothrow = false;
                return Origin.IMMUTABLE;
            }
            if (isImportCall(calleeExpr)) {
                hermetic = false;
                deterministic = false;
                nothrow = false;
                return Origin.ofExternal(call, isDeeplyImmutable(resType));
            }

            // 2. Java method call (fast-path or dynamic reflective overload set)
            Method rm = checker.resolvedJavaMethodOf(call);
            if (rm != null) {
                JavaCallProfile profile = resolveJavaMethodProfile(rm.getDeclaringClass(), rm.getName(), rm);
                BabbleParser.ExpressionContext instanceRecv = Modifier.isStatic(rm.getModifiers()) ? null : receiverExpr;
                return applyJavaCall(call, profile, rm.getParameterTypes(), instanceRecv, recvOrig, argExprs, argOrigins, resType);
            }
            if (calleeType instanceof Type.JavaMethod jm) {
                JavaCallProfile profile = resolveJavaMethodProfile(jm.owner(), jm.name(), null);
                Class<?>[] samTypes = findOverloadSamTypes(jm.owner(), jm.name(), args.size());
                boolean isClassRecv = receiverExpr != null && checker.rawTypeOf(receiverExpr) instanceof Type.JavaClassValue;
                return applyJavaCall(call, profile, samTypes, isClassRecv ? null : receiverExpr, recvOrig, argExprs, argOrigins, resType);
            }

            // 3. Java constructor call or Java functional interface SAM conversion `Predicate(fn)`
            Constructor<?> rc = checker.resolvedJavaConstructorOf(call);
            if (rc != null) {
                JavaCallProfile profile = resolveJavaConstructorProfile(rc.getDeclaringClass(), rc);
                return applyJavaCall(call, profile, rc.getParameterTypes(), null, Origin.IMMUTABLE, argExprs, argOrigins, resType);
            }
            if (calleeType instanceof Type.JavaClassValue jcv) {
                if (jcv.type().isInterface() && args.size() == 1 && JavaTypes.isFunctionalInterface(jcv.type())) {
                    // Explicit SAM wrapper `Function(fn)` is pure deterministic nothrow itself (constructing proxy).
                    return argOrigins[0];
                }
                JavaCallProfile profile = resolveJavaConstructorProfile(jcv.type(), null);
                return applyJavaCall(call, profile, null, null, Origin.IMMUTABLE, argExprs, argOrigins, resType);
            }

            // 4. Babble class constructor call `Point(1, 2)`
            if (calleeType instanceof Type.ClassValue cv && !cv.node().isInterface()) {
                CallableSummary cs = constructorSummary(cv.node());
                if (cs != null) {
                    applyEffects(cs.effects());
                    List<Type.Parameter> cParams = checker.constructorParamsForEffects(cv.node());
                    applyBabbleArgEffects(call, cParams, cs.mutParams(), cs.borrowParams(),
                            null, Origin.IMMUTABLE, false, true, args, argExprs, argOrigins);
                }
                return Origin.ofExternal(call, isDeeplyImmutable(resType));
            }

            // 5. Babble `recurse(...)`
            if (calleeExpr instanceof BabbleParser.RecurseContext && owner instanceof BabbleParser.FunctionLiteralContext selfFn) {
                CallableSummary selfSum = summaries.getOrDefault(selfFn, initialSummary(selfFn));
                applyCallableSummaryEffects(selfSum);
                Type selfRaw = checker.rawTypeOf(selfFn);
                List<Type.Parameter> fParams = selfRaw instanceof Type.Function tf ? tf.parameters() : List.of();
                boolean hasRecv = canReferenceThis(ownerScope);
                Origin selfRecv = hasRecv ? Origin.ofThis(THIS_KEY, false) : Origin.IMMUTABLE;
                applyBabbleArgEffects(call, fParams, selfSum.mutParams(), selfSum.borrowParams(),
                        null, selfRecv, selfSum.effects().mutThis(), selfSum.effects().borrowThis(),
                        args, argExprs, argOrigins);
                return deriveCallReturnOrigin(resType, selfRecv, selfSum.effects().borrowThis(), argOrigins, selfSum.borrowParams(), fParams, args);
            }

            // 6. Babble function / method / union of functions
            if (calleeType instanceof Type.Function || calleeType instanceof Type.Union) {
                return applyBabbleCallableType(call, calleeExpr, calleeType, receiverExpr, recvOrig, args, argExprs, argOrigins, resType);
            }

            // Fallback (unresolved error type)
            return Origin.IMMUTABLE;
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
            if (BlockScopes.staticImport((BabbleParser.CallContext) calleeExpr.getParent()) != null) {
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

        private Origin applyJavaCall(BabbleParser.CallContext call,
                                     JavaCallProfile profile,
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

            // Receiver mut / borrow check:
            Type recvType = receiverExpr != null ? effectiveType(receiverExpr) : null;
            boolean effectiveMutRecv = profile.mutReceiver() && receiverExpr != null && recvType != Type.STRING;
            if (effectiveMutRecv) {
                recordTargetMutation(recvOrig, receiverExpr, "mut Java method " + call.expression().getText(), call);
            }
            if (receiverExpr != null && !profile.borrowReceiver() && !profile.effects().isPure()) {
                markStoredOrigin(recvOrig, "Java method call", call);
            }

            // Parameter mut / borrow / SAM callback checks:
            boolean[] argIsMut = new boolean[argExprs.length];
            for (int i = 0; i < argExprs.length; i++) {
                Type argType = effectiveType(argExprs[i]);
                boolean mutP = i < profile.mutParams().length && profile.mutParams()[i] && argType != Type.STRING;
                boolean borrowP = (i < profile.borrowParams().length && profile.borrowParams()[i]) || profile.effects().isPure();
                argIsMut[i] = mutP;
                if (mutP) {
                    recordTargetMutation(argOrigins[i], argExprs[i], "mut Java parameter", argExprs[i]);
                }
                if (!borrowP) {
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
                        } else if (impl != null && checker.memberTypeForEffects(impl) instanceof Type.Function tf) {
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

        private Origin applyBabbleCallableType(BabbleParser.CallContext call,
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
                applyBabbleArgEffects(call, fn.parameters(), summary.mutParams(), summary.borrowParams(),
                        isMethodCall ? receiverExpr : null, effectiveRecvOrig,
                        isMethodCall && summary.effects().mutThis(),
                        !isMethodCall || summary.effects().borrowThis(),
                        args, argExprs, argOrigins);
                Origin memberOut = deriveCallReturnOrigin(resType, effectiveRecvOrig,
                        !isMethodCall || summary.effects().borrowThis(),
                        argOrigins, summary.borrowParams(), fn.parameters(), args);
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
                for (int i = 0; i < n; i++) {
                    mp[i] = fn.parameters().get(i).mut();
                    bp[i] = fn.parameters().get(i).borrow();
                }
                return new CallableSummary(fn.effects(), mp, bp);
            }

            // Statically resolved concrete Babble function literal (intra-file or imported module):
            BabbleParser.FunctionLiteralContext targetLit = resolveConcreteBabbleTarget(fn, calleeExpr, receiverExpr);
            if (targetLit != null) {
                TypeChecker targetChecker = checker.ownerOf(targetLit);
                EffectChecker targetEC = targetChecker != null && targetChecker.effectChecker != null
                        ? targetChecker.effectChecker : EffectChecker.this;
                ParsedPrefix targetPrefix = targetEC.parseFunctionPrefix(targetLit, false);
                if (!targetPrefix.impure() && !targetPrefix.axiom()) {
                    // Bounded call-site specialization when caller passes concrete callback arguments (§4.5)!
                    CallableSummary specialized = targetEC.trySpecializeCall(targetLit, fn.parameters(), args, argExprs, EffectChecker.this);
                    if (specialized != null) {
                        return lockToDeclaredIfAnnotated(targetLit, targetEC, specialized);
                    }
                }
                CallableSummary base = targetEC == EffectChecker.this
                        ? summaries.get(targetLit)
                        : targetEC.visibleSummaries.get(targetLit);
                if (base != null) {
                    return targetEC == EffectChecker.this && !inSameSccOrUnannotated(targetLit, targetPrefix)
                            ? targetEC.visibleSummaries.getOrDefault(targetLit, base)
                            : base;
                }
            }

            // Abstract interface method, parameter callback, or external Type.Function signature:
            int n = fn.parameters().size();
            boolean[] mp = new boolean[n];
            boolean[] bp = new boolean[n];
            for (int i = 0; i < n; i++) {
                mp[i] = fn.parameters().get(i).mut();
                bp[i] = fn.parameters().get(i).borrow();
            }
            return new CallableSummary(fn.effects(), mp, bp);
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
            return checker.literalOfAcrossModules(fn);
        }

        private void applyBabbleArgEffects(BabbleParser.CallContext call,
                                           List<Type.Parameter> calleeParams,
                                           boolean[] calleeMut,
                                           boolean[] calleeBorrow,
                                           BabbleParser.ExpressionContext receiverExpr,
                                           Origin recvOrig,
                                           boolean mutRecv,
                                           boolean borrowRecv,
                                           List<BabbleParser.CallParameterContext> args,
                                           BabbleParser.ExpressionContext[] argExprs,
                                           Origin[] argOrigins) {
            boolean effectiveMutRecv = mutRecv && (receiverExpr != null || !recvOrig.isEmpty())
                    && (receiverExpr == null || effectiveType(receiverExpr) != Type.STRING);
            if (effectiveMutRecv) {
                recordTargetMutation(recvOrig, receiverExpr != null ? receiverExpr : call,
                        "mut method " + call.expression().getText(), call);
            }
            if (!borrowRecv) {
                markStoredOrigin(recvOrig, "method call " + call.expression().getText(), call);
            }

            boolean[] argIsMut = new boolean[args.size()];
            for (int i = 0; i < args.size(); i++) {
                int pIdx = checker.parameterIndexForArg(calleeParams, args, args.get(i));
                Type argType = effectiveType(argExprs[i]);
                boolean mutP = pIdx >= 0 && pIdx < calleeMut.length && calleeMut[pIdx] && argType != Type.STRING;
                boolean borrowP = pIdx >= 0 && pIdx < calleeBorrow.length && calleeBorrow[pIdx];
                argIsMut[i] = mutP;
                if (mutP) {
                    recordTargetMutation(argOrigins[i], argExprs[i],
                            "mut parameter " + calleeParams.get(pIdx).name(), argExprs[i]);
                }
                if (!borrowP) {
                    markStoredOrigin(argOrigins[i], "call to " + call.expression().getText(), argExprs[i]);
                }
            }

            checkCallExclusivity(receiverExpr, recvOrig, effectiveMutRecv, argExprs, argOrigins, argIsMut, call);
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
                                          BabbleParser.CallContext call) {
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
                    String pName = params.get(p).ID().getText();
                    reportOnce("Cannot mutate parameter " + pName + " (" + reason + ") : mark parameter as mut " + pName, at);
                }
            }
            // In RM002 (BIP007-P1, before `unique`), mutating any non-param/non-this object
            // (including freshly constructed local objects!) drops hermetic/pure!
            if (target.external || !hitParamOrThis) {
                hermetic = false;
                if (isConstrainedPureOrHermetic()) {
                    reportOnce("Cannot mutate object in " + (declaredPrefix.effects().contains(Effect.PURE) ? "pure" : "hermetic")
                            + " function (" + reason + ")", at);
                }
            }
        }

        private void markStoredIntoTarget(Origin target, Origin value, String fieldName, ParserRuleContext at) {
            if (value == Origin.IMMUTABLE) {
                return;
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
                reportOnce("borrow parameter " + params.get(p).ID().getText() + " escapes via " + destination, at);
            }
        }

        void recordReturnOrigin(Origin retOrigin, ParserRuleContext at) {
            if (retOrigin == Origin.IMMUTABLE) {
                return;
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

        private Origin evalIf(BabbleParser.IfExpressionContext ctx) {
            eval(ctx.test);
            TypeChecker.NarrowingInfo nar = checker.extractNarrowingForEffects(ctx.test);
            Map<ParserRuleContext, Origin> snapshot = new IdentityHashMap<>(env);

            Origin thenOrig = withNarrowingMap(nar.whenTrue(), () -> eval(ctx.thenBlock));
            Map<ParserRuleContext, Origin> afterThen = env;

            env = new IdentityHashMap<>(snapshot);
            ParserRuleContext otherwise = ctx.elseBlock != null ? ctx.elseBlock : ctx.elseIf;
            Origin elseOrig = otherwise != null
                    ? withNarrowingMap(nar.whenFalse(), () -> eval(otherwise))
                    : Origin.IMMUTABLE;
            Map<ParserRuleContext, Origin> afterElse = env;

            env = mergeEnvs(afterThen, afterElse);
            return thenOrig.join(elseOrig);
        }

        private Origin evalWhile(BabbleParser.WhileExpressionContext ctx) {
            int iter = 0;
            while (iter++ < 8) {
                Map<ParserRuleContext, Origin> before = new IdentityHashMap<>(env);
                eval(ctx.test);
                TypeChecker.NarrowingInfo nar = checker.extractNarrowingForEffects(ctx.test);
                withNarrowingMap(nar.whenTrue(), () -> eval(ctx.whileBlock));
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
            TypeChecker.NarrowingInfo nar = checker.extractNarrowingForEffects(ctx.left);
            Map<ParserRuleContext, Type> rhsNarrow = ctx.op.getType() == BabbleParser.AND ? nar.whenTrue() : nar.whenFalse();
            Map<ParserRuleContext, Origin> before = new IdentityHashMap<>(env);
            withNarrowingMap(rhsNarrow, () -> eval(ctx.right));
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

        private <T> T withNarrowingMap(Map<ParserRuleContext, Type> map, java.util.function.Supplier<T> action) {
            if (map.isEmpty()) {
                return action.get();
            }
            narrowedScopes.push(map);
            try {
                return action.get();
            } finally {
                narrowedScopes.pop();
            }
        }

        private Type effectiveDeclType(ParserRuleContext decl) {
            for (Map<ParserRuleContext, Type> scope : narrowedScopes) {
                if (scope.containsKey(decl)) {
                    return scope.get(decl);
                }
            }
            return checker.typeOfDeclarationForEffects(decl);
        }

        private Type effectiveType(BabbleParser.ExpressionContext expr) {
            BabbleParser.ExpressionContext unwrapped = unwrapParens(expr);
            if (unwrapped instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
                Object r = checker.resolveNameForEffects(sel.ID().getText(), checker.occurrenceScopeOf(sel));
                if (r instanceof ParserRuleContext decl) {
                    return effectiveDeclType(decl);
                }
            }
            return checker.rawTypeOf(expr);
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
        List<BabbleParser.ParameterDeclarationContext> paramDecls = targetLit.parametersDeclaration().parameterDeclaration();
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
                BabbleParser.FunctionLiteralContext argLit = callerEC.checker.literalOfAcrossModules(argFn);
                if (argLit != null) {
                    CallableSummary argSum = callerEC.summaries.get(argLit);
                    if (argSum != null) {
                        List<Type.Parameter> updatedParams = new ArrayList<>(argFn.parameters().size());
                        for (int k = 0; k < argFn.parameters().size(); k++) {
                            Type.Parameter p = argFn.parameters().get(k);
                            boolean m = k < argSum.mutParams().length && argSum.mutParams()[k];
                            boolean b = k < argSum.borrowParams().length && argSum.borrowParams()[k];
                            updatedParams.add(p.withModifiers(m, b));
                        }
                        argFn = argFn.withEffects(argSum.effects(), updatedParams);
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

    private static boolean anyTrue(boolean[] arr) {
        for (boolean b : arr) {
            if (b) {
                return true;
            }
        }
        return false;
    }
}
