package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.effects.Effect;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.Operators;

/**
 * Compile-time effect, parameter/receiver mutability, borrow-escape, static {@code mut}
 * exclusivity, flow-sensitive {@code nothrow}, and compositional algebraic checker (BIP007-I1;
 * structurally split in BIP027-I3).
 *
 * <p>Delegates syntax/modifier extraction to {@link EffectModifiers}, reflection and 7-tier Java
 * effect resolution to {@link JavaEffectResolver}, algebraic property verification to {@link
 * AlgebraicVerifier}, intra-procedural body walking to {@link EffectBodyWalker}, and call-site
 * effect propagation/specialization to {@link CallEffectAnalyzer}.
 */
final class EffectChecker {

    record ParsedPrefix(
            EnumSet<Effect> effects,
            boolean mutThis,
            boolean borrowThis,
            boolean explicitSharedThis,
            boolean uniqueReturn,
            boolean axiom,
            boolean io,
            boolean explicitVolatile,
            boolean hasAnyModifier) {
        static final ParsedPrefix EMPTY =
                new ParsedPrefix(
                        EnumSet.noneOf(Effect.class),
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false);

        EffectSet toEffectSet() {
            return EffectSet.ofDeclared(
                    effects,
                    mutThis,
                    borrowThis,
                    explicitSharedThis,
                    uniqueReturn,
                    io,
                    explicitVolatile,
                    axiom);
        }
    }

    record ParamMods(boolean mut, boolean shared, boolean unique) {
        static final ParamMods NONE = new ParamMods(false, false, false);
    }

    static final BitSet EMPTY_SUMMARY_BITS = new BitSet(0);

    record CallableSummary(
            EffectSet effects,
            boolean[] mutParams,
            boolean[] borrowParams,
            boolean[] uniqueParams,
            Set<Object> frameReadOwners,
            Set<Object> frameWriteOwners,
            boolean deepMutThis,
            BitSet returnParams,
            boolean returnThis,
            boolean returnExternal,
            boolean returnKnown,
            BitSet ctorStoredParams,
            boolean[] readParams,
            boolean readsThis,
            boolean throwsJavaError) {
        CallableSummary(
                EffectSet effects,
                boolean[] mutParams,
                boolean[] borrowParams,
                boolean[] uniqueParams,
                Set<Object> frameReadOwners,
                Set<Object> frameWriteOwners,
                boolean deepMutThis,
                BitSet returnParams,
                boolean returnThis,
                boolean returnExternal,
                boolean returnKnown,
                BitSet ctorStoredParams,
                boolean[] readParams,
                boolean readsThis) {
            this(
                    effects,
                    mutParams,
                    borrowParams,
                    uniqueParams,
                    frameReadOwners,
                    frameWriteOwners,
                    deepMutThis,
                    returnParams,
                    returnThis,
                    returnExternal,
                    returnKnown,
                    ctorStoredParams,
                    readParams,
                    readsThis,
                    false);
        }

        CallableSummary(
                EffectSet effects,
                boolean[] mutParams,
                boolean[] borrowParams,
                boolean[] uniqueParams,
                Set<Object> frameReadOwners,
                Set<Object> frameWriteOwners,
                boolean deepMutThis,
                BitSet returnParams,
                boolean returnThis,
                boolean returnExternal,
                boolean returnKnown,
                BitSet ctorStoredParams) {
            this(
                    effects,
                    mutParams,
                    borrowParams,
                    uniqueParams,
                    frameReadOwners,
                    frameWriteOwners,
                    deepMutThis,
                    returnParams,
                    returnThis,
                    returnExternal,
                    returnKnown,
                    ctorStoredParams,
                    new boolean[mutParams.length],
                    false,
                    false);
        }

        CallableSummary(
                EffectSet effects,
                boolean[] mutParams,
                boolean[] borrowParams,
                boolean[] uniqueParams,
                Set<Object> frameReadOwners,
                Set<Object> frameWriteOwners,
                boolean deepMutThis) {
            this(
                    effects,
                    mutParams,
                    borrowParams,
                    uniqueParams,
                    frameReadOwners,
                    frameWriteOwners,
                    deepMutThis,
                    EMPTY_SUMMARY_BITS,
                    false,
                    false,
                    false,
                    EMPTY_SUMMARY_BITS,
                    new boolean[mutParams.length],
                    false);
        }

        /**
         * Where this callable sits on the purity lattice it shares with Java call profiles
         * (BIP027-I5).
         */
        Purity purity() {
            return Purity.of(effects, mutParams);
        }

        CallableSummary(
                EffectSet effects,
                boolean[] mutParams,
                boolean[] borrowParams,
                boolean[] uniqueParams,
                Set<Object> frameReadOwners,
                Set<Object> frameWriteOwners) {
            this(
                    effects,
                    mutParams,
                    borrowParams,
                    uniqueParams,
                    frameReadOwners,
                    frameWriteOwners,
                    false,
                    EMPTY_SUMMARY_BITS,
                    false,
                    false,
                    false,
                    EMPTY_SUMMARY_BITS,
                    new boolean[mutParams.length],
                    false);
        }

        CallableSummary(
                EffectSet effects,
                boolean[] mutParams,
                boolean[] borrowParams,
                Set<Object> frameReadOwners,
                Set<Object> frameWriteOwners) {
            this(
                    effects,
                    mutParams,
                    borrowParams,
                    new boolean[mutParams.length],
                    frameReadOwners,
                    frameWriteOwners,
                    false,
                    EMPTY_SUMMARY_BITS,
                    false,
                    false,
                    false,
                    EMPTY_SUMMARY_BITS,
                    new boolean[mutParams.length],
                    false);
        }

        CallableSummary(
                EffectSet effects,
                boolean[] mutParams,
                boolean[] borrowParams,
                boolean[] uniqueParams) {
            this(
                    effects,
                    mutParams,
                    borrowParams,
                    uniqueParams,
                    Set.of(),
                    Set.of(),
                    false,
                    EMPTY_SUMMARY_BITS,
                    false,
                    false,
                    false,
                    EMPTY_SUMMARY_BITS,
                    new boolean[mutParams.length],
                    false);
        }

        CallableSummary(EffectSet effects, boolean[] mutParams, boolean[] borrowParams) {
            this(
                    effects,
                    mutParams,
                    borrowParams,
                    new boolean[mutParams.length],
                    Set.of(),
                    Set.of(),
                    false,
                    EMPTY_SUMMARY_BITS,
                    false,
                    false,
                    false,
                    EMPTY_SUMMARY_BITS,
                    new boolean[mutParams.length],
                    false);
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
                    && throwsJavaError == that.throwsJavaError
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
            int result =
                    Objects.hash(
                            effects,
                            frameReadOwners,
                            frameWriteOwners,
                            deepMutThis,
                            returnParams,
                            returnThis,
                            returnExternal,
                            returnKnown,
                            ctorStoredParams,
                            readsThis,
                            throwsJavaError);
            result = 31 * result + Arrays.hashCode(mutParams);
            result = 31 * result + Arrays.hashCode(borrowParams);
            result = 31 * result + Arrays.hashCode(uniqueParams);
            result = 31 * result + Arrays.hashCode(readParams);
            return result;
        }
    }

    record JavaCallProfile(
            EffectSet effects,
            boolean mutReceiver,
            boolean borrowReceiver,
            boolean[] mutParams,
            boolean[] borrowParams,
            boolean[] uniqueParams,
            boolean explicitNothrow,
            boolean view,
            boolean shallow,
            boolean fresh,
            boolean readsReceiver,
            boolean[] readParams) {
        JavaCallProfile(
                EffectSet effects,
                boolean mutReceiver,
                boolean borrowReceiver,
                boolean[] mutParams,
                boolean[] borrowParams,
                boolean[] uniqueParams,
                boolean explicitNothrow,
                boolean view,
                boolean shallow,
                boolean fresh) {
            this(
                    effects,
                    mutReceiver,
                    borrowReceiver,
                    mutParams,
                    borrowParams,
                    uniqueParams,
                    explicitNothrow,
                    view,
                    shallow,
                    fresh,
                    true,
                    allTrue(mutParams.length));
        }

        JavaCallProfile(
                EffectSet effects,
                boolean mutReceiver,
                boolean borrowReceiver,
                boolean[] mutParams,
                boolean[] borrowParams,
                boolean[] uniqueParams,
                boolean explicitNothrow) {
            this(
                    effects,
                    mutReceiver,
                    borrowReceiver,
                    mutParams,
                    borrowParams,
                    uniqueParams,
                    explicitNothrow,
                    false,
                    false,
                    false,
                    true,
                    allTrue(mutParams.length));
        }

        JavaCallProfile(
                EffectSet effects,
                boolean mutReceiver,
                boolean borrowReceiver,
                boolean[] mutParams,
                boolean[] borrowParams,
                boolean explicitNothrow) {
            this(
                    effects,
                    mutReceiver,
                    borrowReceiver,
                    mutParams,
                    borrowParams,
                    new boolean[mutParams.length],
                    explicitNothrow,
                    false,
                    false,
                    false,
                    true,
                    allTrue(mutParams.length));
        }

        /**
         * Where this Java callee sits on the purity lattice it shares with Babble callable
         * summaries (BIP027-I5).
         */
        Purity purity() {
            return Purity.of(effects, mutParams);
        }

        private static boolean[] allTrue(int n) {
            boolean[] arr = new boolean[n];
            Arrays.fill(arr, true);
            return arr;
        }
    }

    record PendingAxiomDiag(String message, ParserRuleContext at) {}

    final BlockScopes pass;
    final TypedProgram checker;
    final EffectModifiers modifiers;
    final JavaEffectResolver javaResolver;
    final EffectAstExtractors algebraic;

    /** BIP019 structured axioms from imports, session, and this file (tier 1). */
    AxiomData.AxiomTable localAxioms = AxiomData.AxiomTable.EMPTY;

    /** Top-level BIP019 axioms committed when this input belongs to a REPL session. */
    AxiomData.AxiomTable committedAxioms = AxiomData.AxiomTable.EMPTY;

    /** Exported BIP019 axioms published on {@link ModuleInterface}. */
    AxiomData.AxiomTable exportedAxioms = AxiomData.AxiomTable.EMPTY;

    /** Diagnostics recorded while pre-parsing {@code axiomExpressions} before inference. */
    final List<PendingAxiomDiag> pendingAxiomDiags = new ArrayList<>();

    final Map<BabbleParser.FunctionLiteralContext, ParsedPrefix> fnPrefixes =
            new IdentityHashMap<>();
    final Map<BabbleParser.InitExpressionContext, ParsedPrefix> initPrefixes =
            new IdentityHashMap<>();
    final Map<ParserRuleContext, ParamMods> paramModsMap = new IdentityHashMap<>();
    final Set<ParseTree> validatedTypeTrees = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Closures proven not to escape their enclosing function {@code F} (only called or passed to
     * borrow slots).
     */
    final Map<BabbleParser.FunctionLiteralContext, BabbleParser.FunctionLiteralContext>
            frameLocalOwner = new IdentityHashMap<>();

    /**
     * Local defs of {@code F} captured by an *escaping* closure (cannot be treated as private frame
     * state).
     */
    /**
     * The frame locals of enclosing functions that each local closure (by literal) may return : a
     * call to it aliases them in the caller's frame ({@code fn () -> (b)}).
     */
    final Map<ParserRuleContext, Set<ParserRuleContext>> returnedFrameLocals =
            new IdentityHashMap<>();

    final Set<ParserRuleContext> escapedCapturedLocals =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Converged internal summaries for all callables (FunctionLiteralContext and
     * ClassExpressionContext).
     */
    final Map<ParserRuleContext, CallableSummary> summaries = new IdentityHashMap<>();

    /**
     * Single-pass symbolic HOF summaries computed alongside {@link #summaries} ({@code BIP027-I4}).
     */
    final Map<ParserRuleContext, SymbolicEffectSummary> symbolicSummaries = new IdentityHashMap<>();

    final Map<BabbleParser.WhileExpressionContext, Boolean> loopNothrow = new IdentityHashMap<>();

    /**
     * Caller-visible summaries (locked to declared contract for annotated callables per BIP007-D2).
     */
    final Map<ParserRuleContext, CallableSummary> visibleSummaries = new IdentityHashMap<>();

    /** Callables belonging to a cyclic WTO component (self-recursive or mutually recursive). */
    final Set<ParserRuleContext> cyclicCallables =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** Cyclic callables certified terminating by polynomial SCP ({@code BIP027-I6}). */
    final Set<ParserRuleContext> scpTerminatingCallables =
            Collections.newSetFromMap(new IdentityHashMap<>());

    EffectChecker(BlockScopes pass, TypedProgram checker) {
        this.pass = pass;
        this.checker = checker;
        this.modifiers = new EffectModifiers(this);
        this.javaResolver =
                new JavaEffectResolver(
                        pass.hostEffects,
                        checker,
                        () -> this.localAxioms,
                        m -> m.function != null ? summaries.get(m.function) : null);
        this.algebraic = new EffectAstExtractors(this);
        for (ModuleInterface module : pass.modules.values()) {
            localAxioms = localAxioms.mergeOver(module.exportedAxioms());
        }
        if (pass.session != null) {
            localAxioms = localAxioms.mergeOver(pass.session.axioms);
            visibleSummaries.putAll(pass.session.callableSummaries);
        }
        modifiers.collectAxiomExpressions();
    }

    JavaEffectResolver javaResolver() {
        return javaResolver;
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
        return javaResolver.isJavaClassDeeplyImmutable(cls);
    }

    boolean isImmutableConstantValue(Type type) {
        return javaResolver.isImmutableConstantValue(type);
    }

    boolean isCompletelyUntyped(BabbleParser.FunctionLiteralContext fn) {
        return modifiers.isCompletelyUntyped(fn);
    }

    Map<ParserRuleContext, CallableSummary> committedCallableSummaries() {
        return visibleSummaries.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(visibleSummaries);
    }

    ParsedPrefix parseFunctionPrefix(
            BabbleParser.FunctionLiteralContext ctx, boolean reportErrors) {
        return modifiers.parseFunctionPrefix(ctx, reportErrors);
    }

    ParsedPrefix parseFunctionTypePrefix(
            BabbleParser.FunctionTypeContext ctx, boolean reportErrors) {
        return modifiers.parseFunctionTypePrefix(ctx, reportErrors);
    }

    ParsedPrefix parseFunctionTypePrefix(
            BabbleParser.FunctionTypeContext ctx, boolean interfaceMethod, boolean reportErrors) {
        return modifiers.parseFunctionTypePrefix(ctx, interfaceMethod, reportErrors);
    }

    ParamMods parseParamMods(ParserRuleContext param, boolean reportErrors) {
        return modifiers.parseParamMods(param, reportErrors);
    }

    TypedModuleSummary.CallableEffectTemplate buildCallableTemplate(
            BabbleParser.FunctionLiteralContext lit, Type.Function fn) {
        SymbolicEffectSummary sym = symbolicSummaries.get(lit);
        if (sym != null) {
            return new TypedModuleSummary.CallableEffectTemplate(sym);
        }
        CallableSummary base = visibleSummaries.getOrDefault(lit, summaries.get(lit));
        if (base == null || fn == null) {
            return null;
        }
        return new TypedModuleSummary.CallableEffectTemplate(
                SymbolicEffectSummary.fixed(base, true));
    }

    // ================================================================ 2. Pre-Pass & Inference
    // Driver

    void inferAll(BabbleParser.FileContext file) {
        modifiers.collectDeclarations(file);
        classifyClosures(file);

        // Collect all callables in file: FunctionLiteralContext and ClassExpressionContext
        // constructors.
        List<ParserRuleContext> callables = new ArrayList<>();
        collectCallables(file, callables);

        // Initialize summaries.
        for (ParserRuleContext callable : callables) {
            summaries.put(callable, initialSummary(callable));
        }

        // Build call graph among intra-file callables and decompose via Bourdoncle WTO (BIP027-I4).
        Map<ParserRuleContext, Set<ParserRuleContext>> edges = buildCallGraph(callables);
        List<EffectFixpointDriver.WtoElement<ParserRuleContext>> wto =
                EffectFixpointDriver.buildWto(callables, edges);
        cyclicCallables.addAll(EffectFixpointDriver.cyclicNodes(wto));
        for (EffectFixpointDriver.WtoElement<ParserRuleContext> el : wto) {
            if (el instanceof EffectFixpointDriver.Component<ParserRuleContext> comp) {
                List<ParserRuleContext> compNodes = EffectFixpointDriver.componentNodes(comp);
                if (algebraic.certifyComponentTermination(compNodes)) {
                    scpTerminatingCallables.addAll(compNodes);
                }
            }
        }

        EffectFixpointDriver.solve(
                wto,
                summaries,
                this::isFixedSummary,
                (callable, prev) -> recomputeSummary(callable, prev, false));

        // Finalize PURE (= HERMETIC && no mutThis && no mutParams) & algebraic composition,
        // and build caller-visible contracts (BIP007-D2) + finalized symbolic HOF summaries
        // (BIP027-I4).
        for (ParserRuleContext callable : callables) {
            CallableSummary internal = finalizeSummary(callable, summaries.get(callable));
            summaries.put(callable, internal);
            CallableSummary visible = buildVisibleSummary(callable, internal);
            visibleSummaries.put(callable, visible);
            if (callable instanceof BabbleParser.FunctionLiteralContext fn) {
                ParsedPrefix prefix = parseFunctionPrefix(fn, false);
                SymbolicEffectSummary rawSym = symbolicSummaries.get(fn);
                boolean locked =
                        prefix.axiom()
                                || prefix.explicitVolatile()
                                || (prefix.io()
                                        && (rawSym == null || rawSym.calledCallbacks.isEmpty()));
                symbolicSummaries.put(
                        fn,
                        rawSym != null
                                ? rawSym.withFinalizedBase(visible, locked)
                                : SymbolicEffectSummary.fixed(visible, locked));
            }
            pass.analysis.effects().setCallableEffects(callable, visible.effects());
            pass.analysis.effects().setCallableType(callable, checker.rawTypeOf(callable));
        }
    }

    /**
     * Whether a {@code while} loop (its test and body) cannot throw, as last walked ; false if
     * never walked.
     */
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
            JavaCallProfile profile =
                    javaResolver.resolveJavaMethodForCallableSatisfies(jm, target.parameters());
            effects = profile.effects();
            mutParams = profile.mutParams();
            borrowParams = profile.borrowParams();
            borrowRest = profile.borrowReceiver();
            // A Java method value (`Math.abs`, `sb.length`, `xs.add`) is static or already bound to
            // its receiver:
            // it has no `this` parameter at the call site, so it trivially satisfies `borrowThis`,
            // and when assigned
            // to an `io` (or unconstrained) target its bound receiver mutation is covered by `io`.
            boolean keepMutThis =
                    effects.mutThis()
                            && target.effects().hasExplicitAnnotations()
                            && !target.effects().isIO();
            if (!effects.borrowThis() || (effects.mutThis() && !keepMutThis)) {
                effects =
                        new EffectSet(
                                effects.effects(),
                                effects.declared(),
                                keepMutThis,
                                true,
                                false,
                                effects.uniqueResult(),
                                effects.explicitUniqueResult(),
                                effects.terminating(),
                                effects.isIO(),
                                effects.explicitVolatile(),
                                effects.axiomatic());
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
            if (mut && !tp.mut()
                    || (tp.borrow() && !borrow && !javaResolver.isDeeplyImmutable(tp.type()))) {
                return false;
            }
        }
        return true;
    }

    CallableSummary constructorSummary(TypeNode node) {
        return constructorSummary(node, 0);
    }

    CallableSummary constructorSummary(TypeNode node, int ctorIndex) {
        if (node != null && ctorIndex >= 0 && ctorIndex < node.constructors.size()) {
            TypeNode.Constructor ctor = node.constructors.get(ctorIndex);
            ParserRuleContext key = ctor.isPrimary ? node.ctx : ctor.initBlock;
            if (key != null) {
                CallableSummary local = visibleSummaries.get(key);
                if (local != null) {
                    return local;
                }
                CallableSummary internal = summaries.get(key);
                if (internal != null) {
                    return internal;
                }
            }
        }
        return checker.remoteConstructorSummary(node, ctorIndex);
    }

    // ================================================================ 5. Frame-Local Closure
    // Classification

    private void classifyClosures(BabbleParser.FileContext file) {
        List<BabbleParser.FunctionLiteralContext> allFns = new ArrayList<>();
        collectFunctionLiterals(file, allFns);

        // First identify named local closures `def h = (...) -> (...)` inside an enclosing function
        // F.
        Map<BabbleParser.DefExpressionContext, BabbleParser.FunctionLiteralContext> localFnDefs =
                new IdentityHashMap<>();
        for (BabbleParser.FunctionLiteralContext fn : allFns) {
            BabbleParser.FunctionLiteralContext enclosing = checker.enclosingFunctionOf(fn);
            if (enclosing == null) {
                continue;
            }
            if (fn.getParent() instanceof BabbleParser.DefExpressionContext def
                    && def.value == fn) {
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

        // Check if every reference to `def h` stays a direct call or borrow-slot argument inside
        // `enclosing`.
        Set<BabbleParser.DefExpressionContext> escapingDefs =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockScopes.Occurrence occ : pass.occurrences) {
            Object resolved = checker.resolveNameForEffects(occ.name(), occ.scope());
            if (resolved instanceof BabbleParser.DefExpressionContext def
                    && localFnDefs.containsKey(def)) {
                if (occ.ctx() == def) {
                    continue;
                }
                if (!isDirectCalleeOrBorrowArg(occ.ctx())) {
                    escapingDefs.add(def);
                }
            }
        }
        for (Map.Entry<BabbleParser.DefExpressionContext, BabbleParser.FunctionLiteralContext>
                entry : localFnDefs.entrySet()) {
            if (!escapingDefs.contains(entry.getKey())) {
                BabbleParser.FunctionLiteralContext enclosing =
                        checker.enclosingFunctionOf(entry.getValue());
                if (enclosing != null) {
                    frameLocalOwner.put(entry.getValue(), enclosing);
                }
            }
        }

        // Mark local defs captured by any *escaping* closure so they are not treated as private
        // frame locals.
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
        while (p.getParent() instanceof BabbleParser.BlockExpressionContext block
                && block.block().expression().size() == 1) {
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

    private boolean isBorrowCallSlot(
            BabbleParser.CallContext call, BabbleParser.CallParameterContext cp) {
        Type callee = checker.rawTypeOf(call.expression());
        if (callee instanceof Type.Function fn) {
            int paramIdx =
                    checker.parameterIndexForArg(
                            fn.parameters(), call.callParameters().callParameter(), cp);
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

    private boolean parameterNeverEscapesSyntactically(
            BabbleParser.FunctionLiteralContext lit, int paramIdx) {
        List<BabbleParser.FnParamContext> params = lit.fnParameters().fnParam();
        if (paramIdx < 0 || paramIdx >= params.size()) {
            return false;
        }
        BabbleParser.FnParamContext targetParam = params.get(paramIdx);
        for (BlockScopes.Occurrence occ : pass.occurrences) {
            if (checker.resolveNameForEffects(occ.name(), occ.scope()) == targetParam) {
                if (!(occ.ctx().getParent() instanceof BabbleParser.CallContext c
                        && c.expression() == occ.ctx())) {
                    return false;
                }
            }
        }
        return true;
    }

    private Set<BabbleParser.FunctionLiteralContext> activeFrameChain(
            BabbleParser.FunctionLiteralContext start) {
        Set<BabbleParser.FunctionLiteralContext> chain =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (BabbleParser.FunctionLiteralContext cur = frameLocalOwner.get(start);
                cur != null;
                cur = frameLocalOwner.get(cur)) {
            if (!chain.add(cur)) {
                break;
            }
        }
        return chain;
    }

    // ================================================================ 6. Call Graph & SCC Fixed
    // Point

    private static void collectFunctionLiterals(
            ParseTree tree, List<BabbleParser.FunctionLiteralContext> out) {
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
        } else if (tree instanceof BabbleParser.InitExpressionContext init
                && init.fnParameters() != null) {
            out.add(init);
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

    CallableSummary initialSummary(ParserRuleContext callable) {
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
            // Optimistic start for inference/verification: {HERMETIC, DETERMINISTIC, NOTHROW},
            // borrow all non-shared/non-unique, uniqueResult, @TERMINATING!
            EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
            boolean borrowThis = !prefix.explicitSharedThis();
            EffectSet set =
                    new EffectSet(
                            opt,
                            prefix.effects(),
                            prefix.mutThis(),
                            borrowThis,
                            prefix.explicitSharedThis(),
                            true,
                            prefix.uniqueReturn(),
                            true,
                            false,
                            false,
                            false);
            return new CallableSummary(set, mutParams, borrowParams, uniqueParams);
        }
        if (callable instanceof BabbleParser.InitExpressionContext init) {
            return InitEffectAnalyzer.initialInitSummary(this, init);
        }
        // ClassExpressionContext constructor:
        BabbleParser.ClassExpressionContext cls = (BabbleParser.ClassExpressionContext) callable;
        TypeNode clsNode = pass.typeNodes.get(cls);
        boolean clsIsValue = clsNode != null && clsNode.kind == ClassShape.Kind.VALUE_CLASS;
        if (clsNode != null
                && !clsNode.constructors.isEmpty()
                && clsNode.constructors.get(0).isPrimary
                && clsNode.constructors.get(0).initBlock != null) {
            modifiers.parseInitPrefix(clsNode.constructors.get(0).initBlock, clsIsValue, true);
        }
        List<BabbleParser.ClassParamContext> params =
                cls.classParameters() != null ? cls.classParameters().classParam() : List.of();
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
                checker.effectDiagnostic(
                        "value class constructor parameter cannot be mut : "
                                + params.get(i).ID().getText(),
                        params.get(i));
            }
            mutParams[i] = !isValue && pm.mut();
            uniqueParams[i] = pm.unique();
            boolean storedAsField = isValue || ScopeAnalysis.paramIsMember(params.get(i));
            Type pType = checker.typeOfDeclarationForEffects(params.get(i));
            if (storedAsField && !uniqueParams[i] && !javaResolver.isDeeplyImmutable(pType)) {
                ctorStored.set(i);
            }
            borrowParams[i] = !pm.shared() && !uniqueParams[i];
        }
        EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
        return new CallableSummary(
                EffectSet.ofInferred(opt, false, true, ctorStored.isEmpty(), true),
                mutParams,
                borrowParams,
                uniqueParams,
                Set.of(),
                Set.of(),
                false,
                EMPTY_SUMMARY_BITS,
                false,
                false,
                false,
                ctorStored);
    }

    private Map<ParserRuleContext, Set<ParserRuleContext>> buildCallGraph(
            List<ParserRuleContext> callables) {
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

    private void collectCallsInCallable(
            ParseTree tree,
            ParserRuleContext owner,
            Set<ParserRuleContext> callableSet,
            Set<ParserRuleContext> out) {
        if (tree != owner
                && (tree instanceof BabbleParser.FunctionLiteralContext
                        || tree instanceof BabbleParser.ClassExpressionContext
                        || (tree instanceof BabbleParser.InitExpressionContext initTree
                                && initTree.fnParameters() != null))) {
            if (tree instanceof BabbleParser.FunctionLiteralContext innerFn
                    && frameLocalOwner.containsKey(innerFn)) {
                out.add(innerFn);
            }
            return;
        }
        if (tree instanceof BabbleParser.CallContext call) {
            if (pass.analysis.calls().isDelegatingThisCall(call)) {
                ScopeNode ownerScope = checker.scopeOf(owner);
                TypeNode ownerNode = ownerScope != null ? ownerScope.type : null;
                int targetIdx = checker.resolvedConstructorIndexForEffects(call);
                if (ownerNode != null
                        && targetIdx >= 0
                        && targetIdx < ownerNode.constructors.size()) {
                    TypeNode.Constructor target = ownerNode.constructors.get(targetIdx);
                    ParserRuleContext key = target.isPrimary ? ownerNode.ctx : target.initBlock;
                    if (key != null && callableSet.contains(key)) {
                        out.add(key);
                    }
                }
            }
            if (call.expression() instanceof BabbleParser.SelectorContext sel
                    && sel.expression() == null) {
                Object resolved =
                        checker.resolveNameForEffects(
                                sel.ID().getText(), checker.occurrenceScopeOf(sel));
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
                } else if (m instanceof Type.ClassValue cv) {
                    int ctorIdx = checker.resolvedConstructorIndexForEffects(call);
                    ParserRuleContext key = cv.node().ctx;
                    if (ctorIdx >= 0 && ctorIdx < cv.node().constructors.size()) {
                        TypeNode.Constructor c = cv.node().constructors.get(ctorIdx);
                        key = c.isPrimary ? cv.node().ctx : c.initBlock;
                    }
                    if (key != null && callableSet.contains(key)) {
                        out.add(key);
                    }
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
        } else if (tree instanceof BabbleParser.BinaryOpContext bin) {
            String op = bin.op.getText();
            if ("+".equals(op)) {
                addToStringTargets(checker.rawTypeOf(bin.left), callableSet, out);
                addToStringTargets(checker.rawTypeOf(bin.right), callableSet, out);
            }
            String opMethod = Operators.method(op);
            if (opMethod != null) {
                addOperatorTargets(checker.rawTypeOf(bin.left), opMethod, callableSet, out);
            }
        } else if (tree instanceof BabbleParser.NegateContext neg) {
            addOperatorTargets(checker.rawTypeOf(neg.operand), Operators.NEGATE, callableSet, out);
        } else if (tree instanceof BabbleParser.AssignExpressionContext assign) {
            String baseOp = Operators.compound(assign.op.getText());
            String opMethod = baseOp != null ? Operators.method(baseOp) : null;
            if (opMethod != null) {
                addOperatorTargets(checker.rawTypeOf(assign), opMethod, callableSet, out);
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectCallsInCallable(tree.getChild(i), owner, callableSet, out);
        }
    }

    private void addOperatorTargets(
            Type type,
            String methodName,
            Set<ParserRuleContext> callableSet,
            Set<ParserRuleContext> out) {
        if (type == null) {
            return;
        }
        for (Type m : Type.membersOf(TypeAlgebra.unwrapFrozen(type))) {
            if (m instanceof Type.Instance inst) {
                TypeNode.Member mem = checker.findMemberOnNode(inst.node(), methodName);
                if (mem == null) {
                    continue;
                }
                if (mem.function != null && callableSet.contains(mem.function)) {
                    out.add(mem.function);
                }
                Type mt = checker.memberTypeForEffects(inst.node(), mem);
                if (mt instanceof Type.Function tf) {
                    BabbleParser.FunctionLiteralContext lit = checker.literalOf(tf);
                    if (lit != null && callableSet.contains(lit)) {
                        out.add(lit);
                    }
                } else if (mt instanceof Type.Overloaded ov) {
                    for (Type.Function tf : ov.alternatives()) {
                        BabbleParser.FunctionLiteralContext lit = checker.literalOf(tf);
                        if (lit != null && callableSet.contains(lit)) {
                            out.add(lit);
                        }
                    }
                }
            }
        }
    }

    private void addToStringTargets(
            Type type, Set<ParserRuleContext> callableSet, Set<ParserRuleContext> out) {
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

    // ================================================================ 7. Body Transfer & Alias
    // Analyzer

    CallableSummary recomputeSummary(
            ParserRuleContext callable, CallableSummary current, boolean emitDiagnostics) {
        if (callable instanceof BabbleParser.FunctionLiteralContext fn) {
            return analyzeFunctionBody(fn, current, emitDiagnostics);
        }
        if (callable instanceof BabbleParser.InitExpressionContext init) {
            return InitEffectAnalyzer.analyzeInitBody(this, init, current, emitDiagnostics);
        }
        return analyzeConstructorBody(
                (BabbleParser.ClassExpressionContext) callable, current, emitDiagnostics);
    }

    private CallableSummary analyzeFunctionBody(
            BabbleParser.FunctionLiteralContext fn,
            CallableSummary current,
            boolean emitDiagnostics) {
        return analyzeFunctionBody(fn, current, emitDiagnostics, emitDiagnostics);
    }

    private CallableSummary analyzeFunctionBody(
            BabbleParser.FunctionLiteralContext fn,
            CallableSummary current,
            boolean emitDiagnostics,
            boolean verifyCasePurity) {
        ParsedPrefix prefix = parseFunctionPrefix(fn, false);
        List<BabbleParser.FnParamContext> params = fn.fnParameters().fnParam();
        boolean untyped = modifiers.isCompletelyUntyped(fn);

        boolean[] mutParams = current.mutParams().clone();
        boolean[] borrowParams = current.borrowParams().clone();
        boolean mutThis = current.effects().mutThis();
        boolean borrowThis = current.effects().borrowThis();

        EffectBodyWalker analyzer =
                new EffectBodyWalker(
                        this,
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
                        emitDiagnostics,
                        verifyCasePurity);

        // Parameter defaults execute when omitted at call sites.
        for (BabbleParser.FnParamContext p : params) {
            if (p.defaultValue != null) {
                analyzer.eval(p.defaultValue);
            }
        }
        Reachability.Origin bodyResult = analyzer.evalBlock(fn.functionBlock, true);
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
        EffectSet nextSet =
                new EffectSet(
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
        CallableSummary residual =
                new CallableSummary(
                        nextSet,
                        analyzer.mutParams,
                        analyzer.borrowParams,
                        current.uniqueParams(),
                        Set.copyOf(analyzer.frameReadOwners),
                        Set.copyOf(analyzer.frameWriteOwners),
                        analyzer.deepMutThis,
                        (BitSet) analyzer.returnParams.clone(),
                        analyzer.returnThis,
                        analyzer.returnExternal,
                        true,
                        EMPTY_SUMMARY_BITS,
                        analyzer.readParams,
                        analyzer.readsThis,
                        analyzer.throwsJavaError);

        if (analyzer.trackSymbolic && !analyzer.calledCallbacks.isEmpty()) {
            int n = params.size();
            Type.Function[] defaultCbs = new Type.Function[n];
            EffectSet[] defaultCbEffects = new EffectSet[n];
            for (int i = analyzer.calledCallbacks.nextSetBit(0);
                    i >= 0 && i < n;
                    i = analyzer.calledCallbacks.nextSetBit(i + 1)) {
                Type pt = checker.typeOfDeclarationForEffects(params.get(i));
                if (pt instanceof Type.Function tf) {
                    defaultCbs[i] = tf;
                    defaultCbEffects[i] = tf.effects();
                } else {
                    defaultCbEffects[i] = EffectSet.EMPTY;
                }
            }
            SymbolicEffectSummary sym = analyzer.buildSymbolicSummary(residual, defaultCbEffects);
            CallableSummary defaultInst = sym.instantiate(defaultCbs, null);
            symbolicSummaries.put(fn, sym.withFinalizedBase(defaultInst, false));
            return defaultInst;
        }
        if (!emitDiagnostics) {
            symbolicSummaries.put(
                    fn,
                    SymbolicEffectSummary.fixed(
                            residual, prefix.axiom() || prefix.explicitVolatile() || prefix.io()));
        }
        return residual;
    }

    private CallableSummary analyzeConstructorBody(
            BabbleParser.ClassExpressionContext cls,
            CallableSummary current,
            boolean emitDiagnostics) {
        TypeNode node = pass.typeNodes.get(cls);
        boolean isValue = node != null && node.kind == ClassShape.Kind.VALUE_CLASS;
        List<BabbleParser.ClassParamContext> params =
                cls.classParameters() != null ? cls.classParameters().classParam() : List.of();
        boolean[] mutParams = current.mutParams().clone();
        boolean[] borrowParams = current.borrowParams().clone();
        TypeNode.Constructor primaryCtor =
                node != null && !node.constructors.isEmpty() && node.constructors.get(0).isPrimary
                        ? node.constructors.get(0)
                        : null;
        BabbleParser.InitExpressionContext primaryInit =
                primaryCtor != null ? primaryCtor.initBlock : null;
        ParsedPrefix pseudoPrefix =
                primaryInit != null
                        ? modifiers.parseInitPrefix(primaryInit, isValue, false)
                        : isValue
                                ? new ParsedPrefix(
                                        EnumSet.of(
                                                Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC),
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        true)
                                : ParsedPrefix.EMPTY;

        EffectBodyWalker analyzer =
                new EffectBodyWalker(
                        this,
                        cls,
                        checker.scopeOf(cls),
                        params,
                        false,
                        mutParams,
                        borrowParams,
                        current.uniqueParams(),
                        true, // constructor initializes its own fresh `this` fields!
                        true,
                        pseudoPrefix,
                        Set.of(),
                        emitDiagnostics);
        analyzer.constructingSelf = true;

        for (int i = 0; i < params.size(); i++) {
            BabbleParser.ClassParamContext p = params.get(i);
            if (p.defaultValue != null) {
                analyzer.eval(p.defaultValue);
            }
            if ((isValue || ScopeAnalysis.paramIsMember(p))
                    && !current.uniqueParams()[i]
                    && !javaResolver.isDeeplyImmutable(checker.typeOfDeclarationForEffects(p))) {
                analyzer.ctorStoredParams.set(i);
            }
        }
        analyzer.evalClassBody(cls.classBlock);
        if (primaryInit != null) {
            analyzer.evalBlock(primaryInit.initBlock, false, true);
        }
        if (cls.staticBlock != null) {
            // Static block runs once at class definition time; checked separately in verify(), not
            // per construction.
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
        boolean ctorUnique =
                analyzer.hermetic && analyzer.borrowThis && analyzer.ctorStoredParams.isEmpty();
        EffectSet nextSet =
                EffectSet.ofInferred(
                        nextEffects, false, analyzer.borrowThis, ctorUnique, analyzer.terminating);
        return new CallableSummary(
                nextSet,
                analyzer.mutParams,
                analyzer.borrowParams,
                current.uniqueParams(),
                Set.of(),
                Set.of(),
                false,
                EMPTY_SUMMARY_BITS,
                false,
                false,
                false,
                (BitSet) analyzer.ctorStoredParams.clone(),
                analyzer.readParams,
                false,
                analyzer.throwsJavaError);
    }

    CallableSummary finalizeSummary(ParserRuleContext callable, CallableSummary raw) {
        if (isFixedSummary(callable)) {
            return raw;
        }
        EnumSet<Effect> effs = EffectSet.copySet(raw.effects().effects());
        boolean mutThis = raw.effects().mutThis();
        if (raw.purity().mutatesNothing() && !mutThis) {
            effs.add(Effect.PURE);
        } else {
            effs.remove(Effect.PURE);
        }

        // Compositional algebraic property inference:
        if (callable instanceof BabbleParser.FunctionLiteralContext fn) {
            algebraic.inferAlgebraicProperties(fn, effs, raw.mutParams(), mutThis);
        } else if (callable instanceof BabbleParser.ClassExpressionContext cls) {
            TypeNode node = pass.typeNodes.get(cls);
            if (node != null
                    && node.kind == ClassShape.Kind.VALUE_CLASS
                    && effs.contains(Effect.PURE)
                    && effs.contains(Effect.DETERMINISTIC)
                    && cls.classParameters() != null
                    && !cls.classParameters().classParam().isEmpty()
                    && cls.classParameters().classParam().get(0).defaultValue == null) {
                // Value class constructor stores parameter 0 losslessly into field 0 -> injective!
                effs.add(Effect.INJECTIVE);
            }
        }

        EffectSet finalized =
                new EffectSet(
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
        return new CallableSummary(
                finalized,
                raw.mutParams(),
                raw.borrowParams(),
                raw.uniqueParams(),
                raw.frameReadOwners(),
                raw.frameWriteOwners(),
                raw.deepMutThis(),
                raw.returnParams(),
                raw.returnThis(),
                raw.returnExternal(),
                raw.returnKnown(),
                raw.ctorStoredParams(),
                raw.readParams(),
                raw.readsThis(),
                raw.throwsJavaError());
    }

    CallableSummary buildVisibleSummary(ParserRuleContext callable, CallableSummary internal) {
        if (!(callable instanceof BabbleParser.FunctionLiteralContext fn)) {
            return internal;
        }
        if (modifiers.isCompletelyUntyped(fn)) {
            return internal;
        }
        ParsedPrefix prefix = parseFunctionPrefix(fn, false);
        if (prefix.axiom()) {
            return internal;
        }
        // Typed function (BIP016-D2/D3/D4): lock explicit opt-outs (io, volatile, mut, shared,
        // unique),
        // while preserving inferred @NOTHROW, @TERMINATING, algebraic traits, reads, and
        // frame-closure provenance.
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
        boolean ifaceDefault = modifiers.isInterfaceDefault(fn);
        EnumSet<Effect> effs =
                ifaceDefault
                        ? EffectSet.copySet(prefix.effects())
                        : EffectSet.copySet(internal.effects().effects());
        EnumSet<Effect> decl = EffectSet.copySet(prefix.effects());
        boolean mutThis = prefix.mutThis();
        boolean borrowThis = !prefix.explicitSharedThis() && internal.effects().borrowThis();
        boolean uniqueResult =
                ifaceDefault
                        ? prefix.uniqueReturn()
                        : (prefix.uniqueReturn()
                                || (!prefix.io() && internal.effects().uniqueResult()));
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
        EffectSet visSet =
                new EffectSet(
                        effs,
                        decl,
                        mutThis,
                        borrowThis,
                        prefix.explicitSharedThis(),
                        uniqueResult,
                        prefix.uniqueReturn(),
                        terminating,
                        prefix.io(),
                        prefix.explicitVolatile(),
                        false);
        return new CallableSummary(
                visSet,
                visMut,
                visBorrow,
                visUnique,
                internal.frameReadOwners(),
                internal.frameWriteOwners(),
                internal.deepMutThis(),
                internal.returnParams(),
                internal.returnThis(),
                internal.returnExternal(),
                internal.returnKnown(),
                internal.ctorStoredParams(),
                internal.readParams(),
                internal.readsThis(),
                internal.throwsJavaError());
    }

    // ================================================================ 8. Verification Pass

    void verify(BabbleParser.FileContext file) {
        // 1. Validate algebraic modifier applicability across all FunctionTypeContext nodes in
        // file.
        modifiers.validateAllFunctionTypes(file);

        // 2. Verify all callables (annotated functions, value class constructors,
        // mut/borrow/exclusivity).
        List<ParserRuleContext> callables = new ArrayList<>();
        collectCallables(file, callables);
        for (ParserRuleContext callable : callables) {
            verifyCallable(callable);
        }

        // 3. Verify top-level / package / class static blocks for static mut exclusivity and
        // parameter rules.
        verifyTopLevelAndStatics(file);

        // 4. Populate call-site EffectSet map on ScopeAnalysis.
        recordAllCallEffects(file);
    }

    private void verifyCallable(ParserRuleContext callable) {
        if (callable instanceof BabbleParser.FunctionLiteralContext fn) {
            ParsedPrefix prefix = parseFunctionPrefix(fn, false);
            Type rawType = checker.rawTypeOf(fn);
            if (rawType instanceof Type.Function tf) {
                modifiers.validateCallingClause(
                        fn.effectPrefix(), modifiers.declaredBorrows(fn, tf.parameters()));
                if (!prefix.io()) {
                    algebraic.validateAlgebraicSignature(
                            prefix.effects(),
                            tf.parameters(),
                            tf.result(),
                            prefix.mutThis(),
                            prefix.axiom(),
                            fn);
                }
            }
            if (prefix.axiom()) {
                // Axiom body is trusted for effect guarantees, but still enforce case-expression
                // purity.
                analyzeFunctionBody(fn, summaries.get(fn), false, true);
                return;
            }
            // Re-run body analysis with emitDiagnostics = true to report undeclared mut, borrow
            // escape, exclusivity, etc.
            CallableSummary converged = summaries.get(fn);
            CallableSummary verified = finalizeSummary(fn, recomputeSummary(fn, converged, true));

            if (!modifiers.isCompletelyUntyped(fn)) {
                EffectSet actual = verified.effects();
                String fnLabel = ScopeAnalysis.functionName(fn);
                String prefixMsg = fnLabel != null ? "Function " + fnLabel : "Function";
                if (!prefix.io()) {
                    if (!actual.isHermetic()) {
                        if (prefix.explicitVolatile()) {
                            checker.effectDiagnostic(
                                    prefixMsg
                                            + " declared volatile violates hermetic guarantee : mark function as io",
                                    fn);
                        } else {
                            checker.effectDiagnostic(
                                    prefixMsg
                                            + " violates pure guarantee (performs I/O or external mutation) : mark function as io",
                                    fn);
                        }
                    } else if (!prefix.explicitVolatile() && !actual.isDeterministic()) {
                        checker.effectDiagnostic(
                                prefixMsg
                                        + " violates deterministic guarantee : mark function as volatile (or io)",
                                fn);
                    }
                }
                // Check explicit additive guarantees (nothrow, algebraic traits):
                for (Effect req : prefix.effects()) {
                    if (req == Effect.PURE
                            || req == Effect.HERMETIC
                            || req == Effect.DETERMINISTIC) {
                        continue;
                    }
                    if (!actual.has(req)) {
                        checker.effectDiagnostic(
                                prefixMsg
                                        + " declared "
                                        + EffectSet.keywordOf(req)
                                        + " violates "
                                        + EffectSet.keywordOf(req)
                                        + " guarantee",
                                fn);
                    }
                }
                if (prefix.uniqueReturn() && !actual.uniqueResult()) {
                    checker.effectDiagnostic(
                            prefixMsg + " declared unique return violates unique guarantee", fn);
                }
            }
            verifyInheritedAxiomContract(fn, verified);
            return;
        }

        if (callable instanceof BabbleParser.InitExpressionContext init) {
            InitEffectAnalyzer.verifyInit(this, init);
            return;
        }

        // ClassExpressionContext:
        BabbleParser.ClassExpressionContext cls = (BabbleParser.ClassExpressionContext) callable;
        CallableSummary converged = summaries.get(cls);
        CallableSummary verified = finalizeSummary(cls, recomputeSummary(cls, converged, true));
        TypeNode node = pass.typeNodes.get(cls);
        boolean isValue = node != null && node.kind == ClassShape.Kind.VALUE_CLASS;
        BabbleParser.InitExpressionContext primaryInit =
                node != null && !node.constructors.isEmpty() && node.constructors.get(0).isPrimary
                        ? node.constructors.get(0).initBlock
                        : null;
        if (primaryInit != null) {
            ParsedPrefix prefix = modifiers.parseInitPrefix(primaryInit, isValue, false);
            InitEffectAnalyzer.verifyInitContract(
                    this, primaryInit, node, isValue, prefix, verified);
        } else if (isValue) {
            if (!converged.effects().isPure()) {
                checker.effectDiagnostic(
                        "value class " + node.name + " constructor must be pure", cls);
            } else if (!converged.effects().isDeterministic()) {
                checker.effectDiagnostic(
                        "value class " + node.name + " constructor must be deterministic", cls);
            }
        }
    }

    /**
     * Checks a Babble class/object method overriding a Java supertype ({@code Object} or an
     * implemented Java interface) against the most specific inherited non-exact axiom contract
     * ({@code docs/design/bip019_refactoring-axioms.md} §3.2).
     */
    private void verifyInheritedAxiomContract(
            BabbleParser.FunctionLiteralContext fn, CallableSummary verified) {
        ScopeNode fnScope = pass.scopeNodes.get(fn);
        if (fnScope == null || fnScope.memberOf == null) {
            return;
        }
        String methodName = ScopeAnalysis.functionName(fn);
        if (methodName == null) {
            return;
        }
        Set<Class<?>> javaSupertypes = JavaEffectResolver.collectJavaSupertypes(fnScope.memberOf);
        int arity = fn.fnParameters().fnParam().size();
        AxiomData.MemberAxiom contract = null;
        if (!localAxioms.isEmpty()) {
            contract =
                    AxiomData.lookupInheritedContract(
                            javaSupertypes, methodName, arity, localAxioms::directMembers);
        }
        if (contract == null) {
            contract =
                    pass.hostEffects
                            .builtinAxioms()
                            .inheritedContract(javaSupertypes, methodName, arity);
        }
        if (contract == null
                || !JavaEffectResolver.hasJavaMethodWithArity(
                        contract.ownerClass(), methodName, arity)) {
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
            if (!actual.isPure() || actual.mutThis() || !verified.purity().mutatesNothing()) {
                checker.effectDiagnostic(
                        prefixMsg + " violates pure guarantee of " + axiomLabel, fn);
                return;
            }
        } else if (required.isHermetic() && !actual.isHermetic()) {
            checker.effectDiagnostic(
                    prefixMsg + " violates hermetic guarantee of " + axiomLabel, fn);
            return;
        }
        if (required.isDeterministic() && !actual.isDeterministic()) {
            checker.effectDiagnostic(
                    prefixMsg + " violates deterministic guarantee of " + axiomLabel, fn);
        }
        if (required.isNothrow() && !actual.isNothrow()) {
            checker.effectDiagnostic(
                    prefixMsg + " violates nothrow guarantee of " + axiomLabel, fn);
        }
        for (Effect req : required.effects()) {
            if (req == Effect.IDEMPOTENT
                    || req == Effect.COMMUTATIVE
                    || req == Effect.ASSOCIATIVE
                    || req == Effect.MONOTONE
                    || req == Effect.INJECTIVE) {
                if (!actual.has(req)) {
                    checker.effectDiagnostic(
                            prefixMsg
                                    + " violates "
                                    + EffectSet.keywordOf(req)
                                    + " guarantee of "
                                    + axiomLabel,
                            fn);
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
            boolean reqBorrow =
                    contract.hasSignature()
                            ? (i < contract.borrowParams().length && contract.borrowParams()[i])
                            : !contract.sharedReceiver();
            boolean allowMut =
                    contract.hasSignature()
                            && i < contract.mutParams().length
                            && contract.mutParams()[i];
            if (actualMutParams[i] && !allowMut) {
                checker.effectDiagnostic(
                        prefixMsg + " violates pure guarantee of " + axiomLabel, fn);
                break;
            }
            if (reqBorrow && !actualBorrowParams[i]) {
                checker.effectDiagnostic(
                        prefixMsg + " violates borrow guarantee of " + axiomLabel, fn);
                break;
            }
        }
    }

    private void verifyTopLevelAndStatics(BabbleParser.FileContext file) {
        // Run EffectBodyWalker in unconstrained top-level mode to enforce mut exclusivity and
        // unused-pure checks!
        EffectBodyWalker top =
                new EffectBodyWalker(
                        this,
                        file,
                        pass.fileScope,
                        List.of(),
                        false,
                        new boolean[0],
                        new boolean[0],
                        new boolean[0],
                        false,
                        false,
                        ParsedPrefix.EMPTY,
                        Set.of(),
                        true);
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
            checkConstrainedBlock(
                    cls.staticBlock, pass.scopeNodes.get(cls.staticBlock), nextConstraints);
        } else if (tree instanceof BabbleParser.InterfaceExpressionContext iface
                && iface.staticBlock != null) {
            checkConstrainedBlock(
                    iface.staticBlock, pass.scopeNodes.get(iface.staticBlock), nextConstraints);
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            verifyStaticBlocks(tree.getChild(i), nextConstraints);
        }
    }

    private void checkConstrainedBlock(
            BabbleParser.BlockContext block, ScopeNode scope, Set<Effect> constraints) {
        ParsedPrefix req =
                new ParsedPrefix(
                        constraints.isEmpty()
                                ? EnumSet.noneOf(Effect.class)
                                : EnumSet.copyOf(constraints),
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        !constraints.isEmpty());
        EffectBodyWalker ba =
                new EffectBodyWalker(
                        this,
                        block,
                        scope,
                        List.of(),
                        false,
                        new boolean[0],
                        new boolean[0],
                        new boolean[0],
                        false,
                        false,
                        req,
                        Set.of(),
                        true);
        ba.evalBlock(block, false, true);
        if (constraints.contains(Effect.PURE) && !ba.hermetic) {
            checker.effectDiagnostic(
                    "static block inside pure function violates pure guarantee", block);
        } else if (constraints.contains(Effect.HERMETIC) && !ba.hermetic) {
            checker.effectDiagnostic(
                    "static block inside hermetic function violates hermetic guarantee", block);
        } else if (constraints.contains(Effect.DETERMINISTIC) && !ba.deterministic) {
            checker.effectDiagnostic(
                    "static block inside deterministic function violates deterministic guarantee",
                    block);
        }
    }

    private void recordAllCallEffects(ParseTree tree) {
        if (tree instanceof BabbleParser.CallContext call) {
            EffectBodyWalker probe =
                    new EffectBodyWalker(
                            this,
                            call,
                            checker.occurrenceScopeOf(call),
                            List.of(),
                            false,
                            new boolean[0],
                            new boolean[0],
                            new boolean[0],
                            false,
                            false,
                            ParsedPrefix.EMPTY,
                            Set.of(),
                            false);
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
            if (probe.hermetic) {
                EffectSet calleeFx = algebraic.calleeIntrinsicOrResolvedEffects(call);
                if (calleeFx != null && calleeFx.isPure() && calleeFx.isDeterministic()) {
                    for (Effect alg : EffectSet.ALGEBRAIC_ORDER) {
                        if (calleeFx.has(alg)) {
                            effs.add(alg);
                        }
                    }
                }
            }
            pass.analysis
                    .effects()
                    .setCallEffects(
                            call,
                            EffectSet.ofInferred(
                                    effs, false, false, probe.uniqueResult, probe.terminating));
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            recordAllCallEffects(tree.getChild(i));
        }
    }

    static boolean[] allTrue(int n) {
        boolean[] b = new boolean[n];
        for (int i = 0; i < n; i++) {
            b[i] = true;
        }
        return b;
    }

    static List<Type.Parameter> allBorrowParams(List<Type.Parameter> params) {
        List<Type.Parameter> out = new ArrayList<>(params.size());
        for (Type.Parameter p : params) {
            out.add(p.withModifiers(false, true));
        }
        return out;
    }

    static boolean anyTrue(boolean[] arr) {
        for (boolean b : arr) {
            if (b) {
                return true;
            }
        }
        return false;
    }
}
