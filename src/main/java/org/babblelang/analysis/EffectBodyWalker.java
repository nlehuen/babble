package org.babblelang.analysis;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
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
 * Intra-procedural ANTLR body walker and flow-sensitive alias/effect state extracted from {@link
 * EffectChecker} ({@code BIP027-I3}). Delegates call-site analysis to {@link CallEffectAnalyzer}.
 */
final class EffectBodyWalker {
    final EffectChecker ec;
    final CallEffectAnalyzer calls;

    final ParserRuleContext owner;
    final ScopeNode ownerScope;
    final List<? extends ParserRuleContext> params;
    final Map<ParserRuleContext, Integer> paramIndex = new IdentityHashMap<>();
    final boolean inferMut;
    final boolean[] mutParams;
    final boolean[] borrowParams;
    final boolean[] uniqueParams;
    boolean mutThis;
    boolean borrowThis;
    boolean constructingSelf;
    final EffectChecker.ParsedPrefix declaredPrefix;
    final Set<BabbleParser.FunctionLiteralContext> frameChain;
    final boolean emitDiagnostics;

    /**
     * True during single-pass inference of a non-locked function body to build
     * SymbolicEffectSummary (BIP027-I4).
     */
    final boolean trackSymbolic;

    final BitSet calledCallbacks = new BitSet();
    final long[][] symParamFlow;
    final boolean[][] symThisFlow;
    final boolean[][] symExtFlow;

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
    boolean stmtHasSideEffect = false;
    boolean stmtHasError = false;
    ParserRuleContext discardedExpr = null;

    final Set<Object> frameReadOwners = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<Object> frameWriteOwners = Collections.newSetFromMap(new IdentityHashMap<>());

    static final class ActiveForLoop {
        final Reachability.Origin iterableOrigin;
        boolean mutated;

        ActiveForLoop(Reachability.Origin iterableOrigin) {
            this.iterableOrigin = iterableOrigin;
        }
    }

    static final class TryFrame {
        final int level;
        final List<BabbleParser.CatchClauseContext> catches;
        final Reachability.Origin[] caughtOrigins;
        Map<ParserRuleContext, Reachability.Origin> unwindEnv;
        boolean hadLocalThrow;
        boolean hadEscapingThrow;

        TryFrame(
                int level,
                List<BabbleParser.CatchClauseContext> catches,
                Map<ParserRuleContext, Reachability.Origin> preEnv) {
            this.level = level;
            this.catches = catches;
            this.caughtOrigins = new Reachability.Origin[catches.size()];
            this.unwindEnv = new IdentityHashMap<>(preEnv);
        }

        void recordUnwindEnv(
                Map<ParserRuleContext, Reachability.Origin> currentEnv, EffectBodyWalker walker) {
            this.unwindEnv = walker.mergeEnvs(this.unwindEnv, currentEnv);
        }
    }

    Map<ParserRuleContext, Reachability.Origin> env = new IdentityHashMap<>();
    final Set<String> reportedMessages = new HashSet<>();
    final List<ActiveForLoop> activeForIterables = new ArrayList<>();
    final Deque<Reachability.Origin[]> loopBreakOrigins = new ArrayDeque<>();
    final Deque<TryFrame> activeTryStack = new ArrayDeque<>();
    int deepestThrowEscapeLevel = Integer.MAX_VALUE;
    final Set<Integer> forwardedParams;

    /**
     * Callbacks of an enclosing function the owner names in its calling clause (BIP017-D5), tracked
     * as pseudo-parameters with the bits after the owner's own parameters, so that returning or
     * storing one is an escape (BIP017-D6). Never published in ec.summaries.
     */
    final Map<ParserRuleContext, Integer> pseudoForwarded = new IdentityHashMap<>();

    final Map<ParserRuleContext, Reachability.Origin> boundMethodReceivers =
            new IdentityHashMap<>();

    /** Parameters and receiver from which this callable reads mutable state (BIP019 §4). */
    final boolean[] readParams;

    boolean readsThis = false;

    EffectBodyWalker(
            EffectChecker ec,
            ParserRuleContext owner,
            ScopeNode ownerScope,
            List<? extends ParserRuleContext> params,
            boolean inferMut,
            boolean[] mutParams,
            boolean[] borrowParams,
            boolean[] uniqueParams,
            boolean mutThis,
            boolean borrowThis,
            EffectChecker.ParsedPrefix declaredPrefix,
            Set<BabbleParser.FunctionLiteralContext> frameChain,
            boolean emitDiagnostics) {
        this.ec = ec;
        this.calls = new CallEffectAnalyzer(ec, this);
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
        this.emitDiagnostics = emitDiagnostics;
        this.terminating =
                !ec.cyclicCallables.contains(owner) || ec.scpTerminatingCallables.contains(owner);
        this.forwardedParams =
                owner instanceof BabbleParser.FunctionLiteralContext fn
                        ? EffectModifiers.resolveForwardedParamIndices(fn)
                        : owner instanceof BabbleParser.InitExpressionContext init
                                ? EffectModifiers.resolveForwardedParamIndices(init)
                                : Set.of();
        this.trackSymbolic =
                !emitDiagnostics
                        && owner instanceof BabbleParser.FunctionLiteralContext
                        && !declaredPrefix.axiom()
                        && !declaredPrefix.explicitVolatile()
                        && forwardedParams.isEmpty()
                        && !params.isEmpty();
        this.symParamFlow = trackSymbolic ? new long[params.size()][] : null;
        this.symThisFlow = trackSymbolic ? new boolean[params.size()][] : null;
        this.symExtFlow = trackSymbolic ? new boolean[params.size()][] : null;
        if (owner instanceof BabbleParser.FunctionLiteralContext fn
                && EffectModifiers.capturesCallbacks(fn)) {
            for (org.antlr.v4.runtime.tree.TerminalNode id :
                    fn.effectPrefix().callingClause().ID()) {
                ParserRuleContext captured =
                        TypeAnnotations.enclosingParameter(fn.effectPrefix(), id.getText());
                if (captured instanceof BabbleParser.FnParamContext param
                        && EffectModifiers.capturesCallback(fn, param)
                        && !pseudoForwarded.containsKey(param)) {
                    pseudoForwarded.put(param, params.size() + pseudoForwarded.size());
                }
            }
        }

        for (int i = 0; i < params.size(); i++) {
            ParserRuleContext p = params.get(i);
            paramIndex.put(p, i);
            Type pType = ec.checker.typeOfDeclarationForEffects(p);
            boolean isUnique = i < uniqueParams.length && uniqueParams[i];
            if (isUnique) {
                boolean borrowed = i < borrowParams.length && borrowParams[i];
                env.put(
                        p,
                        Reachability.Origin.ofUniqueParam(
                                i, p, ec.javaResolver.isDeeplyImmutable(pType), borrowed));
            } else {
                env.put(
                        p,
                        Reachability.Origin.ofParam(
                                i, p, ec.javaResolver.isDeeplyImmutable(pType)));
            }
        }
    }

    /**
     * Records a read of mutable state from {@code orig} (BIP019 §4.1): deterministic if frozen,
     * deeply immutable, or exclusively owned; composed into {@code readParams}/{@code readsThis}
     * when reached via a parameter or {@code this}; volatile where provenance runs out.
     */
    void recordTargetRead(Reachability.Origin orig, Type targetType) {
        if (targetType == Type.ERROR || targetType == Type.NOTHING || targetType == Type.NULL) {
            return;
        }
        if (containsFrozen(targetType) || ec.javaResolver.isDeeplyImmutable(targetType)) {
            return;
        }
        if (orig == null || orig == Reachability.Origin.IMMUTABLE) {
            return;
        }
        if (orig.owned() && !orig.external && orig.params.isEmpty() && !orig.aliasesThis) {
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

    void demoteUniqueOwner(Object ownerKey, Reachability.Origin taint) {
        if (ownerKey instanceof ParserRuleContext decl) {
            Reachability.Origin cur = env.get(decl);
            if (cur != null && cur.owned()) {
                env.put(decl, cur.demoteAlias(taint));
            }
        }
    }

    void absorbElementIntoLocalContainer(Object ownerKey, Reachability.Origin taint) {
        if (ownerKey instanceof ParserRuleContext decl) {
            Reachability.Origin cur = env.get(decl);
            if (cur != null
                    && cur.owned()
                    && !cur.external
                    && !cur.aliasesThis
                    && cur.params.isEmpty()) {
                env.put(decl, cur.absorbElement(taint));
            }
        }
    }

    boolean isUnattachedUniqueOrImmutable(Reachability.Origin orig, Type type) {
        return orig == Reachability.Origin.IMMUTABLE
                || ec.javaResolver.isDeeplyImmutable(type)
                || (orig.unique && orig.uniqueOwner == null);
    }

    static BabbleParser.ExpressionContext unwrapParens(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext be
                && be.block().expression().size() == 1) {
            expr = be.block().expression(0);
        }
        return expr;
    }

    ParserRuleContext resolveOwnedLocalDecl(BabbleParser.ExpressionContext expr) {
        BabbleParser.ExpressionContext unwrapped = unwrapParens(expr);
        if (!(unwrapped instanceof BabbleParser.SelectorContext sel) || sel.expression() != null) {
            return null;
        }
        ScopeNode occScope = ec.checker.occurrenceScopeOf(sel);
        Object resolved = ec.checker.resolveNameForEffects(sel.ID().getText(), occScope);
        if (!(resolved instanceof ParserRuleContext decl)) {
            return null;
        }
        if (paramIndex.containsKey(decl)) {
            return decl;
        }
        ScopeNode declScope = ec.pass.definitionScopes.get(decl);
        LoweringTargets.DefKind dKind =
                decl instanceof BabbleParser.DefExpressionContext d
                        ? ec.pass.analysis.lowering().defTarget(d).kind()
                        : null;
        if (owner instanceof BabbleParser.FunctionLiteralContext
                && isOwnedScopeLocal(decl, declScope, dKind)) {
            return decl;
        }
        return null;
    }

    Reachability.Origin bindLocalOrigin(
            ParserRuleContext decl, Reachability.Origin rhs, Type localType) {
        if (ec.javaResolver.isDeeplyImmutable(localType)) {
            return Reachability.Origin.IMMUTABLE;
        }
        if (rhs.unique && rhs.uniqueOwner == null) {
            return Reachability.Origin.ofUnique(decl, decl, false);
        }
        if (rhs.localFresh() && rhs.uniqueOwner == null) {
            return new Reachability.Origin(
                    (BitSet) rhs.params.clone(),
                    rhs.aliasesThis,
                    rhs.external,
                    false,
                    rhs.elements(),
                    false,
                    decl,
                    Set.of(decl),
                    new Reachability.PathKey(decl, List.of()));
        }
        if (rhs.owned() && rhs.uniqueOwner != null) {
            // Aliasing a live unique/localFresh local without move(...) drops uniqueness while
            // preserving provenance.
            demoteUniqueOwner(rhs.uniqueOwner, Reachability.Origin.IMMUTABLE);
            return withLocalAliasKey(
                    rhs.demoteAlias(Reachability.Origin.IMMUTABLE), decl, localType);
        }
        return withLocalAliasKey(rhs, decl, localType);
    }

    static boolean containsFrozen(Type type) {
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

    void reportOnce(String msg, ParserRuleContext at) {
        if (!emitDiagnostics) {
            return;
        }
        String key =
                at.getStart().getLine() + ":" + at.getStart().getCharPositionInLine() + ":" + msg;
        if (reportedMessages.add(key)) {
            ec.checker.effectDiagnostic(msg, at);
        }
    }

    boolean isConstrainedPureOrHermetic() {
        return !declaredPrefix.axiom()
                && (declaredPrefix.effects().contains(Effect.PURE)
                        || declaredPrefix.effects().contains(Effect.HERMETIC));
    }

    Reachability.Origin evalBlock(BabbleParser.BlockContext block, boolean isFunctionBody) {
        return evalBlock(block, isFunctionBody, false);
    }

    Reachability.Origin evalBlock(
            BabbleParser.BlockContext block, boolean isFunctionBody, boolean discardLast) {
        if (block == null) {
            return Reachability.Origin.IMMUTABLE;
        }
        List<BabbleParser.ExpressionContext> stmts = block.expression();
        Reachability.Origin last = Reachability.Origin.IMMUTABLE;
        for (int i = 0; i < stmts.size(); i++) {
            BabbleParser.ExpressionContext stmt = stmts.get(i);
            if (i < stmts.size() - 1 || discardLast) {
                evalDiscarded(stmt);
                last = Reachability.Origin.IMMUTABLE;
            } else {
                last = eval(stmt);
            }
            if (!activeTryStack.isEmpty()) {
                for (TryFrame frame : activeTryStack) {
                    frame.recordUnwindEnv(env, this);
                }
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
                || stmt instanceof BabbleParser.BreakExpressionContext
                || stmt instanceof BabbleParser.ContinueExpressionContext
                || stmt instanceof BabbleParser.ThrowExpressionContext) {
            eval(stmt);
            return;
        }
        if (stmt instanceof BabbleParser.BlockExpressionContext be) {
            evalBlock(be.block(), false, true);
            return;
        }
        boolean prevHermetic = hermetic;
        boolean prevNothrow = nothrow;
        boolean prevTerminating = terminating;
        boolean prevSideEffect = stmtHasSideEffect;
        boolean prevError = stmtHasError;
        hermetic = true;
        nothrow = true;
        terminating = true;
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
        boolean stmtTerminating = terminating;
        boolean hadError = stmtHasError;
        hermetic = prevHermetic && hermetic;
        nothrow = prevNothrow && nothrow;
        terminating = prevTerminating && terminating;
        stmtHasSideEffect = prevSideEffect || stmtHasSideEffect || !stmtPure;
        stmtHasError = prevError || hadError;
        if (stmtPure
                && stmtNothrow
                && stmtTerminating
                && !hadError
                && !hasPriorDiagnostic(stmt)
                && !(unwrapParens(stmt) instanceof BabbleParser.CallContext cc
                        && ec.pass.analysis.calls().isDelegatingThisCall(cc))) {
            Type stmtType = ec.checker.rawTypeOf(stmt);
            if (stmtType != Type.ERROR) {
                reportOnce("Unused pure expression", stmt);
            }
        }
    }

    boolean hasPriorDiagnostic(ParserRuleContext stmt) {
        if (stmt == null || stmt.getStart() == null || stmt.getStop() == null) {
            return false;
        }
        if (ec.checker.hasTypeDiagnosticIn(stmt)) {
            return true;
        }
        int startLine = stmt.getStart().getLine();
        int startCol = stmt.getStart().getCharPositionInLine() + 1;
        int endLine = stmt.getStop().getLine();
        int endCol =
                stmt.getStop().getCharPositionInLine()
                        + Math.max(1, stmt.getStop().getText().length());
        for (ScopeAnalysis.Warning w : ec.pass.analysis.typeDiagnostics()) {
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
            if (stmt instanceof BabbleParser.InitExpressionContext) {
                continue;
            }
            if (stmt instanceof BabbleParser.DefExpressionContext def
                    && def.value instanceof BabbleParser.FunctionLiteralContext) {
                // Method declarations don't execute during construction.
                continue;
            }
            evalDiscarded(stmt);
        }
    }

    Reachability.Origin eval(ParserRuleContext ctx) {
        if (ctx == null) {
            return Reachability.Origin.IMMUTABLE;
        }
        if (ctx instanceof BabbleParser.ExpressionContext expr
                && ec.checker.rawTypeOf(expr) == Type.ERROR) {
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
            return Reachability.Origin.IMMUTABLE;
        }
        if (ctx instanceof BabbleParser.ThisExpressionContext) {
            ScopeNode ts = BlockScopes.thisScope(ec.checker.occurrenceScopeOf(ctx));
            Type thisType = ts != null && ts.type != null ? new Type.Instance(ts.type) : Type.ANY;
            return Reachability.Origin.ofThis(
                    Reachability.THIS_KEY, ec.javaResolver.isDeeplyImmutable(thisType));
        }
        if (ctx instanceof BabbleParser.RecurseContext) {
            if (!ec.scpTerminatingCallables.contains(owner)) {
                terminating = false;
            }
            return Reachability.Origin.IMMUTABLE;
        }
        if (ctx instanceof BabbleParser.FunctionLiteralContext lit) {
            return checkClosureCaptureEscape(lit);
        }
        if (ctx instanceof BabbleParser.ClassExpressionContext cls) {
            return InitEffectAnalyzer.evalClassExpression(ec, this, cls);
        }
        if (ctx instanceof BabbleParser.InterfaceExpressionContext iface) {
            return InitEffectAnalyzer.evalInterfaceExpression(ec, this, iface);
        }
        if (ctx instanceof BabbleParser.ObjectExpressionContext obj) {
            return InitEffectAnalyzer.evalObjectExpression(ec, this, obj);
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
            return calls.evalCall(call);
        }
        if (ctx instanceof BabbleParser.IfExpressionContext ifCtx) {
            return evalIf(ifCtx.ifExpr());
        }
        if (ctx instanceof BabbleParser.IfExprContext ifCtx) {
            return evalIf(ifCtx);
        }
        if (ctx instanceof BabbleParser.MatchExpressionContext matchCtx) {
            return evalMatch(matchCtx);
        }
        if (ctx instanceof BabbleParser.WhileExpressionContext whileCtx) {
            return evalWhile(whileCtx);
        }
        if (ctx instanceof BabbleParser.ForExpressionContext forCtx) {
            return evalFor(forCtx);
        }
        if (ctx instanceof BabbleParser.TryExpressionContext tryCtx) {
            return evalTry(tryCtx);
        }
        if (ctx instanceof BabbleParser.BooleanOpContext boolOp) {
            return evalBooleanOp(boolOp);
        }
        if (ctx instanceof BabbleParser.BooleanNotContext notCtx) {
            eval(notCtx.expression());
            return Reachability.Origin.IMMUTABLE;
        }
        if (ctx instanceof BabbleParser.NegateContext neg) {
            eval(neg.operand);
            return Reachability.Origin.IMMUTABLE;
        }
        if (ctx instanceof BabbleParser.BinaryOpContext bin) {
            return evalBinaryOp(bin);
        }
        if (ctx instanceof BabbleParser.RangeExpressionContext range) {
            return evalRange(range);
        }
        if (ctx instanceof BabbleParser.IsExpressionContext isCtx) {
            eval(isCtx.left);
            return Reachability.Origin.IMMUTABLE;
        }
        if (ctx instanceof BabbleParser.NonNullAssertionContext nna) {
            Reachability.Origin orig = eval(nna.expression());
            if (ec.pass.analysis.lowering().nonNullAssertionTarget(nna) != null) {
                nothrow = false;
            }
            Type dst = ec.checker.rawTypeOf(nna);
            return ec.javaResolver.isDeeplyImmutable(dst) ? Reachability.Origin.IMMUTABLE : orig;
        }
        if (ctx instanceof BabbleParser.OverloadExpressionContext ov) {
            Reachability.Origin joined = Reachability.Origin.IMMUTABLE;
            for (BabbleParser.ExpressionContext op : ov.expression()) {
                Reachability.Origin arm = eval(op);
                if (arm != Reachability.Origin.IMMUTABLE) {
                    joined = joined == Reachability.Origin.IMMUTABLE ? arm : joined.join(arm);
                }
            }
            return joined;
        }
        if (ctx instanceof BabbleParser.TypeApplyContext ta) {
            return eval(ta.expression());
        }
        if (ctx instanceof BabbleParser.CoreCollectionContext
                || ctx instanceof BabbleParser.CoreExceptionRefContext) {
            return Reachability.Origin.IMMUTABLE;
        }
        if (ctx instanceof BabbleParser.AsExpressionContext asCtx) {
            Reachability.Origin leftOrig = eval(asCtx.left);
            Type src = effectiveType(asCtx.left);
            Type dst = ec.checker.rawTypeOf(asCtx);
            if (!isRedundantCast(src, dst)) {
                nothrow = false;
            }
            return ec.javaResolver.isDeeplyImmutable(dst)
                    ? Reachability.Origin.IMMUTABLE
                    : leftOrig;
        }
        if (ctx instanceof BabbleParser.ReturnExpressionContext ret) {
            stmtHasSideEffect = true;
            Reachability.Origin val =
                    ret.expression() != null
                            ? eval(ret.expression())
                            : Reachability.Origin.IMMUTABLE;
            recordReturnOrigin(val, ret);
            return Reachability.Origin.IMMUTABLE;
        }
        if (ctx instanceof BabbleParser.BreakExpressionContext brk) {
            stmtHasSideEffect = true;
            Reachability.Origin val =
                    brk.expression() != null
                            ? eval(brk.expression())
                            : Reachability.Origin.IMMUTABLE;
            Reachability.Origin[] acc = loopBreakOrigins.peek();
            if (acc != null) {
                acc[0] = acc[0] == null ? val : acc[0].join(val);
            }
            return Reachability.Origin.IMMUTABLE;
        }
        if (ctx instanceof BabbleParser.ContinueExpressionContext) {
            stmtHasSideEffect = true;
            return Reachability.Origin.IMMUTABLE;
        }
        if (ctx instanceof BabbleParser.ThrowExpressionContext throwCtx) {
            return evalThrow(throwCtx);
        }
        if (ctx instanceof BabbleParser.SuperExpressionContext) {
            return Reachability.Origin.ofThis(Reachability.THIS_KEY, false);
        }
        return Reachability.Origin.IMMUTABLE;
    }

    Reachability.Origin evalDef(BabbleParser.DefExpressionContext def) {
        stmtHasSideEffect = true;
        LoweringTargets.DefKind kind = ec.pass.analysis.lowering().defTarget(def).kind();
        if (kind == LoweringTargets.DefKind.REQUIRED) {
            return Reachability.Origin.IMMUTABLE;
        }
        Reachability.Origin init =
                def.value != null ? eval(def.value) : Reachability.Origin.IMMUTABLE;
        ScopeNode defScope = ec.pass.definitionScopes.get(def);
        Type defType = ec.checker.typeOfDeclarationForEffects(def);
        if (kind == LoweringTargets.DefKind.MEMBER) {
            if (!constructingSelf) {
                hermetic = false;
            }
            if (init.unique && init.uniqueOwner != null) {
                demoteUniqueOwner(init.uniqueOwner, Reachability.Origin.IMMUTABLE);
            }
            markStoredOrigin(init, "field " + def.name.getText(), def);
        } else if (isOwnedScopeLocal(def, defScope, kind)) {
            // Local variable in this function (or top-level binding when verifying top-level
            // file/static block)!
            Reachability.Origin localOrig =
                    owner instanceof BabbleParser.FunctionLiteralContext
                            ? bindLocalOrigin(def, init, defType)
                            : (ec.javaResolver.isDeeplyImmutable(defType)
                                    ? Reachability.Origin.IMMUTABLE
                                    : withLocalAliasKey(init, def, defType));
            env.put(def, localOrig);
        } else {
            if (init.unique && init.uniqueOwner != null) {
                demoteUniqueOwner(init.uniqueOwner, Reachability.Origin.IMMUTABLE);
            }
            markStoredOrigin(init, "outer binding " + def.name.getText(), def);
        }
        return Reachability.Origin.IMMUTABLE;
    }

    boolean isOwnedScopeLocal(
            ParserRuleContext decl, ScopeNode declScope, LoweringTargets.DefKind kind) {
        if (kind == LoweringTargets.DefKind.STATIC
                || declScope == null
                || ec.escapedCapturedLocals.contains(decl)) {
            return false;
        }
        BabbleParser.FunctionLiteralContext declFn = enclosingFunctionOfDecl(decl);
        if (owner instanceof BabbleParser.FunctionLiteralContext) {
            return declFn == owner && declScope.kind != ScopeNode.Kind.FILE;
        }
        if (owner instanceof BabbleParser.FileContext
                || owner instanceof BabbleParser.BlockContext) {
            return declFn == null;
        }
        return InitEffectAnalyzer.isInitOwnedLocal(
                owner, decl, declFn, enclosingFunctionOfDecl(owner));
    }

    Reachability.Origin withLocalAliasKey(Reachability.Origin base, Object localKey, Type type) {
        if (ec.javaResolver.isDeeplyImmutable(type)) {
            return Reachability.Origin.IMMUTABLE;
        }
        Set<Object> keys = new HashSet<>(base.directKeys);
        keys.add(localKey);
        return new Reachability.Origin(
                (BitSet) base.params.clone(),
                base.aliasesThis,
                base.external,
                Set.copyOf(keys),
                base.pathKey != null
                        ? base.pathKey
                        : new Reachability.PathKey(localKey, List.of()));
    }

    Reachability.Origin evalAssign(BabbleParser.AssignExpressionContext assign) {
        stmtHasSideEffect = true;
        String compoundOp = Operators.compound(assign.op.getText());
        BabbleParser.ExpressionContext recv = ScopeAnalysis.assignReceiver(assign);
        String assignedName = ScopeAnalysis.assignName(assign);
        if (recv == null) {
            Reachability.Origin rhs = eval(assign.value);
            if (compoundOp != null) {
                checkCompoundArithmeticEffects(compoundOp, ec.checker.rawTypeOf(assign), assign);
            }
            ScopeNode occScope = ec.checker.occurrenceScopeOf(assign);
            Object resolved = ec.checker.resolveNameForEffects(assignedName, occScope);
            if (resolved instanceof ParserRuleContext decl) {
                if (compoundOp != null) {
                    Reachability.Origin cur = env.get(decl);
                    if (cur != null && cur.moved) {
                        reportOnce("Use of moved variable " + assignedName, assign);
                    }
                }
                if (paramIndex.containsKey(decl)) {
                    // Local parameter variable reassignment (rebinds local slot, does NOT mutate
                    // caller's object!).
                    Type pType = ec.checker.typeOfDeclarationForEffects(decl);
                    Reachability.Origin merged =
                            compoundOp != null
                                    ? Reachability.Origin.IMMUTABLE
                                    : bindLocalOrigin(decl, rhs, pType);
                    env.put(decl, merged);
                    return merged;
                }
                BabbleParser.FunctionLiteralContext declFn = enclosingFunctionOfDecl(decl);
                ScopeNode declScope = ec.pass.definitionScopes.get(decl);
                LoweringTargets.DefKind dKind =
                        decl instanceof BabbleParser.DefExpressionContext d
                                ? ec.pass.analysis.lowering().defTarget(d).kind()
                                : null;
                if (isOwnedScopeLocal(decl, declScope, dKind)) {
                    Type dType = ec.checker.typeOfDeclarationForEffects(decl);
                    Reachability.Origin next =
                            compoundOp != null
                                    ? Reachability.Origin.IMMUTABLE
                                    : (owner instanceof BabbleParser.FunctionLiteralContext
                                            ? bindLocalOrigin(decl, rhs, dType)
                                            : withLocalAliasKey(rhs, decl, dType));
                    env.put(decl, next);
                    return next;
                }
                if (declFn != null
                        && frameChain.contains(declFn)
                        && !ec.escapedCapturedLocals.contains(decl)) {
                    // Frame-local (`borrow`) helper closure reassigning enclosing F's unescaped
                    // local `def` (§4.5)!
                    frameWriteOwners.add(declFn);
                    if (rhs.unique && rhs.uniqueOwner != null) {
                        demoteUniqueOwner(rhs.uniqueOwner, Reachability.Origin.IMMUTABLE);
                    }
                    markStoredOrigin(rhs, "outer variable " + assignedName, assign);
                    return rhs;
                }
                // Outer / package / file / static variable write -> breaks hermetic/pure!
                hermetic = false;
                if (rhs.unique && rhs.uniqueOwner != null) {
                    demoteUniqueOwner(rhs.uniqueOwner, Reachability.Origin.IMMUTABLE);
                }
                markStoredOrigin(rhs, "outer variable " + assignedName, assign);
                return rhs;
            }
            hermetic = false;
            return rhs;
        }

        // Member assignment: `receiver.field = value` (or compound `+=`, etc.)
        Reachability.Origin recvOrig = eval(recv);
        Reachability.Origin rhs = eval(assign.value);
        if (compoundOp != null) {
            checkCompoundArithmeticEffects(compoundOp, ec.checker.rawTypeOf(assign), assign);
        }
        Type recvType = effectiveType(recv);
        if (recvType == Type.ERROR
                || recvType == Type.ANY
                || Type.containsNull(recvType)
                || Type.isCoreScalar(recvType)
                || recvType instanceof Type.Function) {
            return rhs;
        }
        if (recvType instanceof Type.JavaClassValue
                || recvType instanceof Type.ClassValue
                || recvType instanceof Type.Module) {
            // Static / module field write -> global mutation!
            hermetic = false;
            if (rhs.unique && rhs.uniqueOwner != null) {
                demoteUniqueOwner(rhs.uniqueOwner, Reachability.Origin.IMMUTABLE);
            }
            markStoredOrigin(rhs, "static member " + assignedName, assign);
            return rhs;
        }
        if (constructingSelf && InitEffectAnalyzer.isDirectThisExpr(recv)) {
            return InitEffectAnalyzer.recordConstructingFieldStore(
                    this, rhs, effectiveType(assign.value), assignedName, assign);
        }
        recordTargetMutation(recvOrig, recv, "field assignment ." + assignedName, assign, true);
        markStoredIntoTarget(recvOrig, rhs, effectiveType(assign.value), assignedName, assign);
        return rhs;
    }

    void checkCompoundArithmeticEffects(
            String op, Type resType, BabbleParser.AssignExpressionContext assign) {
        if ("+".equals(op) && resType == Type.STRING) {
            applyEffects(ec.javaResolver.toStringProfile(effectiveType(assign.value)));
        } else if ("//".equals(op)
                && !(resType instanceof Type.Instance)
                && !isNonZeroIntegerConstant(assign.value)) {
            nothrow = false;
        }
    }

    Reachability.Origin evalSelector(BabbleParser.SelectorContext sel) {
        if (sel.expression() == null) {
            ScopeNode occScope = ec.checker.occurrenceScopeOf(sel);
            Object resolved = ec.checker.resolveNameForEffects(sel.ID().getText(), occScope);
            if (resolved instanceof ParserRuleContext decl) {
                Type declType = ec.checker.rawTypeOf(sel);
                boolean imm = ec.javaResolver.isDeeplyImmutable(declType);
                boolean narrowedImm =
                        declType != null
                                && declType != Type.ERROR
                                && declType != Type.NOTHING
                                && imm;
                if (paramIndex.containsKey(decl)) {
                    Reachability.Origin orig =
                            env.getOrDefault(
                                    decl,
                                    Reachability.Origin.ofParam(paramIndex.get(decl), decl, imm));
                    if (orig.moved) {
                        reportOnce("Use of moved variable " + sel.ID().getText(), sel);
                    }
                    return (!orig.moved && narrowedImm) ? Reachability.Origin.IMMUTABLE : orig;
                }
                if (pseudoForwarded.containsKey(decl)) {
                    return Reachability.Origin.ofParam(pseudoForwarded.get(decl), decl, false);
                }
                BabbleParser.FunctionLiteralContext declFn = enclosingFunctionOfDecl(decl);
                ScopeNode declScope = ec.pass.definitionScopes.get(decl);
                LoweringTargets.DefKind dKind =
                        decl instanceof BabbleParser.DefExpressionContext d
                                ? ec.pass.analysis.lowering().defTarget(d).kind()
                                : null;
                if (isOwnedScopeLocal(decl, declScope, dKind)) {
                    Reachability.Origin orig =
                            env.getOrDefault(decl, Reachability.Origin.ofExternal(decl, imm));
                    if (orig.moved) {
                        reportOnce("Use of moved variable " + sel.ID().getText(), sel);
                    }
                    return (!orig.moved && narrowedImm) ? Reachability.Origin.IMMUTABLE : orig;
                }
                if (declFn != null
                        && frameChain.contains(declFn)
                        && !ec.escapedCapturedLocals.contains(decl)) {
                    // Frame-local helper closure reading F's unescaped local `def`!
                    frameReadOwners.add(declFn);
                    return Reachability.Origin.ofExternal(decl, imm);
                }
                // Outer / top-level / package binding read:
                if (ec.checker.isReassigned(decl) || !isImmutableOuterBinding(decl, declType)) {
                    deterministic = false;
                }
                return Reachability.Origin.ofExternal(decl, imm);
            }
            if (resolved == null) {
                Type selType = ec.checker.rawTypeOf(sel);
                if (selType == Type.ANY || selType == Type.NULLABLE_ANY || selType == Type.ERROR) {
                    deterministic = false;
                    nothrow = false;
                }
            }
            return Reachability.Origin.IMMUTABLE;
        }

        // Member read `receiver.field` or `receiver?.field`:
        Reachability.Origin recvOrig = eval(sel.expression());
        Type recvType = effectiveType(sel.expression());
        Type resType = ec.checker.rawTypeOf(sel);
        boolean immRes = ec.javaResolver.isDeeplyImmutable(resType);
        String fieldName = ScopeAnalysis.selectorName(sel);

        // Check Java static or instance field access:
        LoweringTargets.JavaFieldTarget jft = ec.checker.javaFieldTargetOf(sel);
        if (jft != null) {
            Field jf = jft.field();
            if (Modifier.isStatic(jf.getModifiers())) {
                if (!Modifier.isFinal(jf.getModifiers())) {
                    deterministic = false;
                }
            } else if (!Modifier.isFinal(jf.getModifiers())) {
                recordTargetRead(recvOrig, recvType);
            }
            if (ec.checker.hasCallCheck(sel)) {
                nothrow = false;
            }
            return immRes ? Reachability.Origin.IMMUTABLE : recvOrig.deriveMember(fieldName, false);
        }
        if (recvType instanceof Type.JavaClassValue jcv) {
            Field f = JavaEffectResolver.findJavaField(jcv.type(), fieldName);
            if (f != null
                    && Modifier.isStatic(f.getModifiers())
                    && !Modifier.isFinal(f.getModifiers())) {
                deterministic = false;
            }
            if (ec.checker.hasCallCheck(sel)) {
                nothrow = false;
            }
            return Reachability.Origin.ofExternal(null, immRes);
        }
        if (recvType instanceof Type.ClassValue cv) {
            TypeNode.Member sm = cv.node().statics.get(fieldName);
            if (sm != null
                    && (sm.isMutable() || !ec.javaResolver.isImmutableConstantValue(resType))) {
                deterministic = false;
            }
            return Reachability.Origin.ofExternal(null, immRes);
        }
        if (recvType instanceof Type.Module mod) {
            if (!ec.checker.isModuleExportImmutable(mod.module(), fieldName, resType)) {
                deterministic = false;
            }
            return Reachability.Origin.ofExternal(null, immRes);
        }
        if (ec.checker.hasCallCheck(sel)) {
            nothrow = false;
        }
        boolean isFunctionType = resType instanceof Type.Function;
        if (isFunctionType) {
            boundMethodReceivers.put(sel, recvOrig);
        }
        if (readsMutableBabbleMember(recvType, fieldName, isFunctionType)) {
            recordTargetRead(recvOrig, recvType);
        }
        return recvOrig.deriveMember(fieldName, immRes);
    }

    boolean readsMutableBabbleMember(Type recvType, String fieldName, boolean isFunctionType) {
        if (recvType == null
                || containsFrozen(recvType)
                || ec.javaResolver.isDeeplyImmutable(recvType)) {
            return false;
        }
        for (Type m : Type.membersOf(Type.withoutNull(recvType))) {
            if (m instanceof Type.Instance inst) {
                ClassShape.Kind kind = inst.node().kind;
                if (kind == ClassShape.Kind.VALUE_CLASS) {
                    continue;
                }
                if (kind == ClassShape.Kind.INTERFACE) {
                    if (!isFunctionType) {
                        return true;
                    }
                    continue;
                }
                TypeNode.Member member = inst.node().members.get(fieldName);
                if (member == null || member.isMutable()) {
                    return true;
                }
            } else if (m instanceof Type.Intersection inter) {
                for (Type part : inter.members()) {
                    if (readsMutableBabbleMember(part, fieldName, isFunctionType)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean isImmutableOuterBinding(ParserRuleContext decl, Type type) {
        if (ec.checker.isReassigned(decl)) {
            return false;
        }
        return ec.javaResolver.isImmutableConstantValue(type);
    }

    void recordTargetMutation(
            Reachability.Origin target,
            ParserRuleContext targetExpr,
            String reason,
            ParserRuleContext at,
            boolean shallow) {
        stmtHasSideEffect = true;
        if (targetExpr instanceof BabbleParser.ExpressionContext expr
                && effectiveType(expr) == Type.ERROR) {
            return;
        }
        if (!activeForIterables.isEmpty()) {
            boolean reported = false;
            for (ActiveForLoop loop : activeForIterables) {
                if (mayOverlapIteratedCollection(loop.iterableOrigin, target)) {
                    loop.mutated = true;
                }
                if (!reported && isConstrainedPureOrHermetic() && !target.directKeys.isEmpty()) {
                    for (Object k : target.directKeys) {
                        if (loop.iterableOrigin.directKeys.contains(k)) {
                            reportOnce("Cannot mutate collection while iterating over it", at);
                            reported = true;
                            break;
                        }
                    }
                }
            }
        }
        // In BIP007-I2 & BIP016-D9, shallow mutation of a fresh local container (or any mutation of
        // a unique local/param)
        // stays confined (hermetic/pure) without tainting stored elements!
        if ((target.unique || (target.localFresh() && shallow))
                && !target.external
                && target.params.isEmpty()
                && !target.aliasesThis) {
            return;
        }
        Reachability.Origin effectiveTarget =
                (!shallow && target.hasElementTaint()) ? target.flattenElements() : target;
        boolean hitParamOrThis = false;
        if (effectiveTarget.aliasesThis) {
            hitParamOrThis = true;
            if (!shallow || !effectiveTarget.directKeys.contains(Reachability.THIS_KEY)) {
                deepMutThis = true;
            }
            if (constructingSelf) {
                // Allowed: initializing `this` inside constructor.
            } else if (mutThis) {
                // Explicitly declared or already inferred mutThis.
            } else if (inferMut) {
                mutThis = true;
            } else {
                reportOnce(
                        "Cannot mutate this (" + reason + ") : function is not declared mut", at);
            }
        }
        for (int p = effectiveTarget.params.nextSetBit(0);
                p >= 0;
                p = effectiveTarget.params.nextSetBit(p + 1)) {
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
                reportOnce(
                        "Cannot mutate parameter "
                                + pName
                                + " ("
                                + reason
                                + ") : mark parameter as mut "
                                + pName,
                        at);
            }
        }
        if (effectiveTarget.external || !hitParamOrThis) {
            hermetic = false;
            if (isConstrainedPureOrHermetic()) {
                reportOnce(
                        "Cannot mutate external object in "
                                + (declaredPrefix.explicitVolatile() ? "volatile" : "pure")
                                + " function ("
                                + reason
                                + ") : mark function as io",
                        at);
            }
        }
    }

    void markStoredIntoTarget(
            Reachability.Origin target,
            Reachability.Origin value,
            Type valueType,
            String fieldName,
            ParserRuleContext at) {
        if (value == Reachability.Origin.IMMUTABLE) {
            return;
        }
        if (target.owned() && !target.external && target.params.isEmpty() && !target.aliasesThis) {
            // Storing into a fresh local container: absorb element provenance without escaping yet!
            if (!isUnattachedUniqueOrImmutable(value, valueType)) {
                absorbElementIntoLocalContainer(target.uniqueOwner, value);
                if (value.owned() && value.uniqueOwner != null) {
                    demoteUniqueOwner(value.uniqueOwner, Reachability.Origin.IMMUTABLE);
                }
            }
            return;
        }
        if (constructingSelf && target.aliasesThis && target.params.isEmpty() && !target.external) {
            // Storing into `this`'s own fields during construction stays confined inside the new
            // object!
            Reachability.Origin flat = value.flattenElements();
            ctorStoredParams.or(flat.params);
            if (value.owned() && value.uniqueOwner != null) {
                demoteUniqueOwner(value.uniqueOwner, Reachability.Origin.IMMUTABLE);
            }
            return;
        }
        if (value.owned() && value.uniqueOwner != null) {
            demoteUniqueOwner(value.uniqueOwner, Reachability.Origin.IMMUTABLE);
        }
        // Storing `this` (or a sub-reference of `this`) back into `this`'s own field does not
        // escape `this`!
        boolean storingIntoSelfThis =
                target.aliasesThis && target.params.isEmpty() && !target.external;
        markStoredOrigin(value, "field ." + fieldName, at, storingIntoSelfThis);
    }

    void markStoredOrigin(Reachability.Origin value, String destination, ParserRuleContext at) {
        markStoredOrigin(value, destination, at, false);
    }

    void markStoredOrigin(
            Reachability.Origin value,
            String destination,
            ParserRuleContext at,
            boolean ignoreThis) {
        if (value == Reachability.Origin.IMMUTABLE) {
            return;
        }
        Reachability.Origin flat = value.flattenElements();
        if (flat.aliasesThis && !ignoreThis) {
            if (borrowThis) {
                borrowThis = false;
            }
            if (!inferMut
                    && owner instanceof BabbleParser.FunctionLiteralContext
                    && !declaredPrefix.explicitSharedThis()) {
                reportOnce(
                        "Borrowed this escapes via " + destination + " : mark function as shared",
                        at);
            }
        }
        for (int p = flat.params.nextSetBit(0); p >= 0; p = flat.params.nextSetBit(p + 1)) {
            markParamEscaped(p, destination, at);
        }
    }

    /** The name of a pseudo-parameter (a captured callback the owner forwards), or null. */
    String pseudoForwardedName(int bit) {
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
                reportOnce(
                        "Parameter in calling clause escapes via "
                                + destination
                                + " : remove "
                                + name
                                + " from calling and mark it as shared",
                        at);
            }
            return;
        }
        if (borrowParams[p]) {
            borrowParams[p] = false;
        }
        if (!inferMut
                && (owner instanceof BabbleParser.FunctionLiteralContext
                        || owner instanceof BabbleParser.InitExpressionContext)) {
            EffectChecker.ParamMods declared = ec.modifiers.parseParamMods(params.get(p), false);
            if (!declared.shared() && !declared.unique()) {
                String pName = ScopeAnalysis.paramId(params.get(p)).getText();
                boolean inCalling =
                        owner instanceof BabbleParser.FunctionLiteralContext fnOwner
                                ? EffectModifiers.inCallingClause(fnOwner, pName)
                                : EffectModifiers.inCallingClause(
                                        (BabbleParser.InitExpressionContext) owner, pName);
                if (inCalling) {
                    // calling forwards f's effects only while the call runs: an escaping f
                    // must leave the clause and be shared, whatever calls it later.
                    reportOnce(
                            "Parameter in calling clause escapes via "
                                    + destination
                                    + " : remove "
                                    + pName
                                    + " from calling and mark it as shared",
                            at);
                    return;
                }
                reportOnce(
                        "Borrowed parameter "
                                + pName
                                + " escapes via "
                                + destination
                                + " : mark parameter as shared "
                                + pName,
                        at);
            }
        }
    }

    void recordReturnOrigin(Reachability.Origin retOrigin, ParserRuleContext at) {
        if (retOrigin == Reachability.Origin.IMMUTABLE) {
            return;
        }
        Reachability.Origin flat = retOrigin.flattenElements();
        if (!retOrigin.unique || !flat.params.isEmpty() || flat.aliasesThis || flat.external) {
            uniqueResult = false;
        } else if (at instanceof BabbleParser.ReturnExpressionContext
                && retOrigin.uniqueOwner instanceof ParserRuleContext decl) {
            env.put(decl, Reachability.Origin.MOVED);
        }
        // A forwarded callback is only accounted for while the call runs : returning it (or a
        // value aliasing it, such as a closure capturing it) would let it run unaccounted
        // (BIP017-D6).
        if (!inferMut) {
            for (int p = flat.params.nextSetBit(0); p >= 0; p = flat.params.nextSetBit(p + 1)) {
                String name =
                        p >= params.size()
                                ? pseudoForwardedName(p)
                                : forwardedParams.contains(p)
                                        ? ScopeAnalysis.paramId(params.get(p)).getText()
                                        : null;
                if (name != null) {
                    reportOnce(
                            "Parameter in calling clause escapes via return : remove "
                                    + name
                                    + " from calling",
                            at);
                }
            }
        }
        // BIP016-D10: returning a @BORROWED parameter or `this` does not store it; record return
        // provenance for callers!
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
     * The origin of a closure value (BIP017-D6) : capturing a parameter or {@code this} aliases it,
     * like returning it does (BIP016-D10), so the closure carries their provenance and escapes only
     * where the closure itself is stored. Captured locals are still stored on capture, their later
     * assignments being outer-variable stores.
     */
    Reachability.Origin checkClosureCaptureEscape(BabbleParser.FunctionLiteralContext inner) {
        boolean frameLocal = ec.frameLocalOwner.get(inner) == owner;
        EffectChecker.CallableSummary innerSum = ec.summaries.get(inner);
        Resolution res = ec.pass.analysis.resolution();
        if (res == null) {
            return Reachability.Origin.IMMUTABLE;
        }
        Reachability.Origin closure = Reachability.Origin.IMMUTABLE;
        boolean capturesThis = innerContainsThis(inner);
        if (capturesThis) {
            if (!frameLocal) {
                closure =
                        Reachability.Origin.ofThis(Reachability.THIS_KEY, false)
                                .stripDirectIdentity(false);
            }
            if (innerSum != null && innerSum.effects().mutThis() && !mutThis) {
                if (inferMut) {
                    mutThis = true;
                } else {
                    reportOnce(
                            "Inner closure mutates this : enclosing function is not declared mut",
                            inner);
                }
            }
        }
        for (Resolution.Variable cap : res.captured(inner)) {
            ParserRuleContext decl = cap.declaration();
            if (decl == null) {
                continue;
            }
            Reachability.Origin orig = env.get(decl);
            if (orig == null && paramIndex.containsKey(decl)) {
                orig =
                        Reachability.Origin.ofParam(
                                paramIndex.get(decl),
                                decl,
                                ec.javaResolver.isDeeplyImmutable(
                                        ec.checker.typeOfDeclarationForEffects(decl)));
            } else if (orig == null && pseudoForwarded.containsKey(decl)) {
                orig = Reachability.Origin.ofParam(pseudoForwarded.get(decl), decl, false);
            }
            if (orig == null || frameLocal) {
                continue;
            }
            if (paramIndex.containsKey(decl) || pseudoForwarded.containsKey(decl)) {
                Reachability.Origin flat = orig.flattenElements();
                Reachability.Origin alias = flat.stripDirectIdentity(false);
                closure = closure == Reachability.Origin.IMMUTABLE ? alias : closure.join(alias);
            } else {
                markStoredOrigin(orig, "captured closure", inner);
            }
        }
        return closure;
    }

    boolean innerContainsThis(ParseTree tree) {
        if (tree instanceof BabbleParser.ClassExpressionContext
                || tree instanceof BabbleParser.ObjectExpressionContext
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

    Reachability.Origin evalIf(BabbleParser.IfExprContext ctx) {
        eval(ctx.test);
        Map<ParserRuleContext, Reachability.Origin> snapshot = new IdentityHashMap<>(env);

        Reachability.Origin thenOrig = eval(ctx.thenBlock);
        Map<ParserRuleContext, Reachability.Origin> afterThen = env;

        env = new IdentityHashMap<>(snapshot);
        ParserRuleContext otherwise = ctx.elseBlock != null ? ctx.elseBlock : ctx.elseIf;
        Reachability.Origin elseOrig =
                otherwise != null ? eval(otherwise) : Reachability.Origin.IMMUTABLE;
        Map<ParserRuleContext, Reachability.Origin> afterElse = env;

        env = mergeEnvs(afterThen, afterElse);
        return thenOrig.join(elseOrig);
    }

    Reachability.Origin evalMatch(BabbleParser.MatchExpressionContext ctx) {
        Reachability.Origin scrutOrig = eval(ctx.scrutinee);
        Type scrutType = effectiveType(ctx.scrutinee);
        if (ctx.elseClause() == null
                && MatchExhaustiveness.containsExternalClosedJavaType(scrutType)) {
            // Runtime defense-in-depth throw if external Java enum/sealed adds a variant (BIP038
            // §5.3).
            nothrow = false;
        }
        Reachability.Origin activeScrutOrig = scrutOrig;
        Map<ParserRuleContext, Reachability.Origin> fallthroughEnv = new IdentityHashMap<>(env);
        List<Map<ParserRuleContext, Reachability.Origin>> armEnvs = new ArrayList<>();
        Reachability.Origin resultOrig = null;

        for (BabbleParser.CaseClauseContext cc : ctx.caseClause()) {
            env = new IdentityHashMap<>(fallthroughEnv);
            ParserRuleContext topUniqueBind =
                    evalPatternEffects(cc.pattern(), activeScrutOrig, scrutType, true);
            if (cc.guard != null) {
                eval(cc.guard);
                // If a guarded arm's guard moved a consumed unique scrutinee binding, it is no
                // longer unique downstream.
                if (topUniqueBind != null) {
                    Reachability.Origin afterGuard = env.get(topUniqueBind);
                    if (afterGuard != null && afterGuard.moved) {
                        activeScrutOrig = Reachability.Origin.MOVED;
                    }
                }
            }
            // Any moves/effects in pattern or guard apply to later arms too.
            fallthroughEnv = new IdentityHashMap<>(env);
            if (topUniqueBind != null) {
                fallthroughEnv.remove(topUniqueBind);
            }
            Reachability.Origin armOrig = eval(cc.armBlock);
            armEnvs.add(env);
            resultOrig = resultOrig == null ? armOrig : resultOrig.join(armOrig);
        }
        if (ctx.elseClause() != null) {
            env = new IdentityHashMap<>(fallthroughEnv);
            BabbleParser.ElseClauseContext ecNode = ctx.elseClause();
            if (ecNode.name != null) {
                env.put(ecNode, bindPatternOrigin(ecNode, activeScrutOrig, true));
            }
            Reachability.Origin elseOrig = eval(ecNode.elseBlock);
            armEnvs.add(env);
            resultOrig = resultOrig == null ? elseOrig : resultOrig.join(elseOrig);
        } else {
            armEnvs.add(fallthroughEnv);
        }
        Map<ParserRuleContext, Reachability.Origin> merged = armEnvs.get(0);
        for (int i = 1; i < armEnvs.size(); i++) {
            merged = mergeEnvs(merged, armEnvs.get(i));
        }
        env = merged;
        return resultOrig != null ? resultOrig : Reachability.Origin.IMMUTABLE;
    }

    private Reachability.Origin bindPatternOrigin(
            ParserRuleContext decl, Reachability.Origin sourceOrig, boolean isTopLevel) {
        Type boundType = ec.checker.typeOfDeclarationForEffects(decl);
        if (boundType == null || ec.javaResolver.isDeeplyImmutable(boundType)) {
            return Reachability.Origin.IMMUTABLE;
        }
        if (sourceOrig.moved) {
            return Reachability.Origin.MOVED;
        }
        // Consuming scrutinee (`match move(x)` or fresh construction): uniqueOwner == null
        // transfers @UNIQUE to top-level bindings.
        if (isTopLevel && sourceOrig.unique && sourceOrig.uniqueOwner == null) {
            return new Reachability.Origin(
                    (BitSet) sourceOrig.params.clone(),
                    sourceOrig.aliasesThis,
                    sourceOrig.external,
                    true,
                    sourceOrig.elements(),
                    false,
                    decl,
                    Set.of(decl),
                    new Reachability.PathKey(decl, List.of()));
        }
        // Borrowing scrutinee (`match x`) or nested destructured field (`is R(def f)`):
        // inherit @BORROWED provenance pointing back to scrutinee without stealing uniqueness or
        // demoting `x` (BIP038-D9).
        Reachability.Origin flat = sourceOrig.flattenElements();
        Set<Object> keys = new HashSet<>(flat.directKeys);
        keys.add(decl);
        return new Reachability.Origin(
                (BitSet) flat.params.clone(),
                flat.aliasesThis,
                flat.external,
                false,
                null,
                false,
                null,
                Set.copyOf(keys),
                new Reachability.PathKey(decl, List.of()));
    }

    private ParserRuleContext evalPatternEffects(
            BabbleParser.PatternContext pat,
            Reachability.Origin scrutOrig,
            Type scrutType,
            boolean isTopLevel) {
        ParserRuleContext topUniqueBind = null;
        for (BabbleParser.AtomicPatternContext atom : pat.atomicPattern()) {
            switch (atom) {
                case BabbleParser.ValuePatternContext vp -> {
                    if (BlockScopes.isWildcardExpr(vp.expr)) {
                        continue;
                    }
                    LoweringMetadata.RangePatternSource rps = ec.checker.matchRangePattern(vp);
                    if (rps != null) {
                        eval(rps.lo());
                        eval(rps.hi());
                        if (rps.step() != null) {
                            eval(rps.step());
                            if (!isPositiveIntegerConstant(rps.step())) {
                                nothrow = false;
                            }
                        }
                        if (rps.elemKind() == PrimitiveKind.NONE && !rps.stringElement()) {
                            Type elem = Type.withoutNull(scrutType);
                            applyEffects(ec.javaResolver.comparisonProfile(elem, elem));
                        }
                    } else {
                        eval(vp.expr);
                    }
                }
                case BabbleParser.RelationalPatternContext rp -> {
                    eval(rp.rhs);
                    Type rhsType = effectiveType(rp.rhs);
                    String op = rp.op.getText();
                    if ("==".equals(op) || "!=".equals(op)) {
                        applyEffects(ec.javaResolver.equalityProfile(scrutType, rhsType));
                    } else {
                        applyEffects(
                                ec.javaResolver.comparisonProfile(
                                        Type.withoutNull(scrutType), rhsType));
                    }
                }
                case BabbleParser.MembershipPatternContext mp -> {
                    Reachability.Origin contOrig = eval(mp.container);
                    Type contType = effectiveType(mp.container);
                    LoweringTargets.MembershipTarget mt = ec.checker.matchMembershipTarget(mp);
                    if (mt != null) {
                        switch (mt.kind()) {
                            case DYNAMIC -> {
                                recordTargetRead(contOrig, contType);
                                deterministic = false;
                                nothrow = false;
                            }
                            case ARRAY -> recordTargetRead(contOrig, contType);
                            case COLLECTION -> {
                                recordTargetRead(contOrig, contType);
                                Type unwrapped = TypeAlgebra.unwrapFrozen(contType);
                                Class<?> cls =
                                        unwrapped instanceof Type.JavaInstance ji
                                                ? ji.type()
                                                : java.util.Collection.class;
                                applyEffects(
                                        ec.javaResolver
                                                .resolveJavaMethodProfile(
                                                        cls,
                                                        "contains",
                                                        JavaEffectResolver.lookupPublicMethod(
                                                                cls, "contains", Object.class))
                                                .effects());
                            }
                            case MAP -> {
                                recordTargetRead(contOrig, contType);
                                Type unwrapped = TypeAlgebra.unwrapFrozen(contType);
                                Class<?> cls =
                                        unwrapped instanceof Type.JavaInstance ji
                                                ? ji.type()
                                                : java.util.Map.class;
                                applyEffects(
                                        ec.javaResolver
                                                .resolveJavaMethodProfile(
                                                        cls,
                                                        "containsKey",
                                                        JavaEffectResolver.lookupPublicMethod(
                                                                cls, "containsKey", Object.class))
                                                .effects());
                            }
                            case RANGE -> {}
                        }
                    }
                }
                case BabbleParser.BindingPatternContext bp -> {
                    Reachability.Origin bound = bindPatternOrigin(bp, scrutOrig, isTopLevel);
                    env.put(bp, bound);
                    if (isTopLevel && bound.unique && bound.uniqueOwner == bp) {
                        topUniqueBind = bp;
                    }
                    if (bp.patternArgs() != null) {
                        evalPatternArgsEffects(bp, bp.patternArgs(), scrutOrig, scrutType);
                    }
                }
                case BabbleParser.TypeOrDestructurePatternContext tp -> {
                    if (tp.patternArgs() != null) {
                        evalPatternArgsEffects(tp, tp.patternArgs(), scrutOrig, scrutType);
                    }
                }
                default -> {}
            }
        }
        return topUniqueBind;
    }

    private void evalPatternArgsEffects(
            ParserRuleContext owner,
            BabbleParser.PatternArgsContext args,
            Reachability.Origin scrutOrig,
            Type scrutType) {
        MatchTyper.RecordDestructureInfo recInfo = ec.checker.matchRecordDestructure(owner);
        MatchTyper.SequenceDestructureInfo seqInfo = ec.checker.matchSequenceDestructure(owner);
        if (recInfo != null && recInfo.isJavaRecord()) {
            for (java.lang.reflect.Method acc : recInfo.javaAccessors()) {
                applyEffects(
                        ec.javaResolver
                                .resolveJavaMethodProfile(
                                        acc.getDeclaringClass(), acc.getName(), acc)
                                .effects());
            }
        }
        if (seqInfo != null) {
            recordTargetRead(scrutOrig, scrutType);
        }
        List<BabbleParser.PatternArgContext> argList = args.patternArg();
        for (int i = 0; i < argList.size(); i++) {
            Type subType = Type.NULLABLE_ANY;
            if (recInfo != null && i < recInfo.fieldTypes().size()) {
                subType = recInfo.fieldTypes().get(i);
            } else if (seqInfo != null) {
                subType = seqInfo.elemType();
            }
            evalPatternEffects(argList.get(i).pattern(), scrutOrig, subType, false);
        }
        if (args.restPattern() != null && args.restPattern().name != null) {
            BabbleParser.RestPatternContext rp = args.restPattern();
            Reachability.Provenance elemProv = Reachability.Provenance.EMPTY;
            if (seqInfo != null && !ec.javaResolver.isDeeplyImmutable(seqInfo.elemType())) {
                Reachability.Origin flat = scrutOrig.flattenElements();
                elemProv =
                        new Reachability.Provenance(flat.params, flat.aliasesThis, flat.external);
            }
            env.put(
                    rp,
                    new Reachability.Origin(
                            Reachability.Origin.EMPTY_BITS,
                            false,
                            false,
                            true,
                            elemProv,
                            false,
                            rp,
                            Set.of(rp),
                            new Reachability.PathKey(rp, List.of())));
        }
    }

    Reachability.Origin evalWhile(BabbleParser.WhileExpressionContext ctx) {
        terminating = false;
        stmtHasSideEffect = true;
        Reachability.Origin[] breakAcc = new Reachability.Origin[1];
        loopBreakOrigins.push(breakAcc);
        Reachability.Origin bodyOrig = Reachability.Origin.IMMUTABLE;
        // Whether the loop itself can throw, for loops that never end (BIP024-D8).
        boolean outerNothrow = nothrow;
        nothrow = true;
        try {
            int iter = 0;
            while (true) {
                Map<ParserRuleContext, Reachability.Origin> before = new IdentityHashMap<>(env);
                eval(ctx.test);
                bodyOrig = eval(ctx.whileBlock);
                Map<ParserRuleContext, Reachability.Origin> merged =
                        iter++ < 2 ? mergeEnvs(before, env) : widenEnvs(before, env);
                if (envsEqual(merged, before)) {
                    env = merged;
                    break;
                }
                env = merged;
            }
        } finally {
            loopBreakOrigins.pop();
            ec.loopNothrow.put(ctx, nothrow);
            nothrow = outerNothrow && nothrow;
        }
        return breakAcc[0] != null ? bodyOrig.join(breakAcc[0]) : bodyOrig;
    }

    private static boolean mayOverlapIteratedCollection(
            Reachability.Origin iterOrig, Reachability.Origin target) {
        if (iterOrig == null || target == null) {
            return false;
        }
        for (Object k : target.directKeys) {
            if (iterOrig.directKeys.contains(k)) {
                return true;
            }
        }
        if (target.owned() && !target.external && target.params.isEmpty() && !target.aliasesThis) {
            return false;
        }
        if (iterOrig.owned()
                && !iterOrig.external
                && iterOrig.params.isEmpty()
                && !iterOrig.aliasesThis) {
            return false;
        }
        return iterOrig.params.intersects(target.params)
                || (iterOrig.aliasesThis && target.aliasesThis)
                || (iterOrig.external && target.external);
    }

    Reachability.Origin evalFor(BabbleParser.ForExpressionContext ctx) {
        Reachability.Origin iterOrig = eval(ctx.iterable);
        Type iterType = effectiveType(ctx.iterable);
        TypeChecker.ForIterableInfo info = ec.checker.forIterableInfo(ctx);
        boolean trackConcurrentMut = false;
        boolean curatedFinite = false;
        boolean isArrayLoop = false;
        if (info != null) {
            recordTargetRead(iterOrig, iterType);
            switch (info.kind()) {
                case ARRAY, COUNTED_RANGE -> {
                    // Indexed array or counted discrete range loop : @HERMETIC @NOTHROW,
                    // terminates.
                    isArrayLoop = true;
                }
                case JAVA_ITERABLE -> {
                    boolean curatedNothrow = false;
                    Type unwrapped = iterType instanceof Type.Frozen f ? f.of() : iterType;
                    if (unwrapped instanceof Type.JavaInstance ji) {
                        if (ji.type() == org.babblelang.runtime.Range.class) {
                            curatedFinite = true;
                            curatedNothrow = true;
                        } else {
                            EffectChecker.JavaCallProfile iterProf =
                                    ec.javaResolver.resolveJavaMethodProfile(
                                            ji.type(),
                                            "iterator",
                                            JavaEffectResolver.lookupPublicMethod(
                                                    ji.type(), "iterator"));
                            applyEffects(iterProf.effects());
                            if (iterProf.mutReceiver()) {
                                recordTargetMutation(
                                        iterOrig, ctx.iterable, "iterator", ctx.iterable, false);
                            }
                            if (iterProf.view()) {
                                trackConcurrentMut = true;
                                if (iterProf.effects().isHermetic()
                                        && iterProf.effects().terminating()) {
                                    curatedFinite = true;
                                    curatedNothrow = iterProf.effects().isNothrow();
                                }
                            }
                        }
                    }
                    if (!curatedFinite) {
                        terminating = false;
                    }
                    if (!curatedNothrow) {
                        nothrow = false;
                    }
                }
                case JAVA_ITERATOR -> {
                    terminating = false;
                    nothrow = false;
                    recordTargetMutation(
                            iterOrig, ctx.iterable, "iterator.next", ctx.iterable, true);
                }
                case BABBLE_TO_JAVA_ITERABLE -> {
                    terminating = false;
                    nothrow = false;
                    if (info.iteratorFn() != null) {
                        applyEffects(info.iteratorFn().effects());
                        if (info.iteratorFn().effects().mutThis()) {
                            recordTargetMutation(
                                    iterOrig, ctx.iterable, "iterator", ctx.iterable, false);
                        }
                    }
                }
                case BABBLE_ITERABLE -> {
                    terminating = false;
                    if (info.iteratorFn() != null) {
                        applyEffects(info.iteratorFn().effects());
                        if (info.iteratorFn().effects().mutThis()) {
                            recordTargetMutation(
                                    iterOrig, ctx.iterable, "iterator", ctx.iterable, false);
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
                            recordTargetMutation(
                                    iterOrig, ctx.iterable, "hasNext", ctx.iterable, false);
                        }
                    }
                    if (info.nextFn() != null) {
                        applyEffects(info.nextFn().effects());
                        if (info.nextFn().effects().mutThis()) {
                            recordTargetMutation(
                                    iterOrig, ctx.iterable, "next", ctx.iterable, false);
                        }
                    }
                }
                case DYNAMIC_UNION -> {
                    terminating = false;
                    nothrow = false;
                }
            }
        }
        Type loopVarType = ec.checker.typeOfDeclarationForEffects(ctx);
        Reachability.Origin elemOrig;
        if (loopVarType == null || ec.javaResolver.isDeeplyImmutable(loopVarType)) {
            elemOrig = Reachability.Origin.IMMUTABLE;
        } else {
            Reachability.Origin flat = iterOrig.flattenElements();
            elemOrig =
                    new Reachability.Origin(
                            flat.params,
                            flat.aliasesThis,
                            flat.external,
                            false,
                            null,
                            false,
                            null,
                            Set.of(ctx),
                            new Reachability.PathKey(ctx, List.of()));
        }
        Reachability.Origin[] breakAcc = new Reachability.Origin[1];
        loopBreakOrigins.push(breakAcc);
        ActiveForLoop activeLoop = trackConcurrentMut ? new ActiveForLoop(iterOrig) : null;
        if (activeLoop != null) {
            activeForIterables.add(activeLoop);
        }
        boolean outerHermetic = hermetic;
        boolean outerNothrow = nothrow;
        boolean outerTerminating = terminating;
        hermetic = true;
        nothrow = true;
        terminating = true;
        Reachability.Origin bodyOrig = Reachability.Origin.IMMUTABLE;
        try {
            int iter = 0;
            while (true) {
                Map<ParserRuleContext, Reachability.Origin> before = new IdentityHashMap<>(env);
                env.put(ctx, elemOrig);
                bodyOrig = eval(ctx.forBlock);
                env.remove(ctx);
                Map<ParserRuleContext, Reachability.Origin> merged =
                        iter++ < 2 ? mergeEnvs(before, env) : widenEnvs(before, env);
                if (envsEqual(merged, before)) {
                    env = merged;
                    break;
                }
                env = merged;
            }
        } finally {
            boolean bodyHermetic = hermetic;
            boolean bodyNothrow = nothrow;
            boolean bodyTerminating = terminating;
            hermetic = outerHermetic && bodyHermetic;
            nothrow = outerNothrow && bodyNothrow;
            terminating = outerTerminating && bodyTerminating;
            boolean loopMutated = activeLoop != null && activeLoop.mutated;
            if (activeLoop != null) {
                activeForIterables.remove(activeForIterables.size() - 1);
                if (curatedFinite && (loopMutated || !bodyHermetic)) {
                    terminating = false;
                    nothrow = false;
                }
            }
            if (emitDiagnostics) {
                boolean loopFinite = isArrayLoop || (curatedFinite && !loopMutated && bodyHermetic);
                boolean loopNothrowOk = loopFinite && bodyNothrow && (isArrayLoop || outerNothrow);
                EnumSet<Effect> fx = EnumSet.noneOf(Effect.class);
                if (bodyHermetic) {
                    fx.add(Effect.PURE);
                    fx.add(Effect.HERMETIC);
                }
                if (loopNothrowOk) {
                    fx.add(Effect.NOTHROW);
                }
                ec.pass
                        .analysis
                        .effects()
                        .setForEffects(
                                ctx,
                                EffectSet.ofInferred(
                                        fx, false, false, false, loopFinite && bodyTerminating));
            }
            loopBreakOrigins.pop();
        }
        return breakAcc[0] != null ? bodyOrig.join(breakAcc[0]) : bodyOrig;
    }

    Reachability.Origin evalThrow(BabbleParser.ThrowExpressionContext ctx) {
        stmtHasSideEffect = true;
        Reachability.Origin orig = eval(ctx.expression());
        Type exprType = effectiveType(ctx.expression());
        boolean locallyCaught = false;
        if (exprType != null && exprType != Type.ERROR && exprType != Type.NOTHING) {
            for (TryFrame frame : activeTryStack) {
                for (int i = 0; i < frame.catches.size(); i++) {
                    BabbleParser.CatchClauseContext cc = frame.catches.get(i);
                    Type catchType = ec.checker.catchTargetType(cc);
                    boolean matches;
                    if (cc.type() == null) {
                        matches = !TypeChecker.mayBeJavaError(exprType);
                    } else if (catchType != null && catchType != Type.ERROR) {
                        matches = ec.checker.isCatchSubtype(exprType, catchType);
                    } else {
                        matches = false;
                    }
                    if (matches) {
                        frame.hadLocalThrow = true;
                        deepestThrowEscapeLevel = Math.min(deepestThrowEscapeLevel, frame.level);
                        if (orig != Reachability.Origin.IMMUTABLE) {
                            Reachability.Origin routed = orig.flattenElements();
                            if (orig.unique && orig.uniqueOwner != null) {
                                demoteUniqueOwner(orig.uniqueOwner, Reachability.Origin.IMMUTABLE);
                                routed = routed.demoteAlias(Reachability.Origin.IMMUTABLE);
                            }
                            frame.caughtOrigins[i] =
                                    frame.caughtOrigins[i] == null
                                            ? routed
                                            : frame.caughtOrigins[i].join(routed);
                        }
                        locallyCaught = true;
                        break;
                    }
                }
                if (locallyCaught) {
                    break;
                }
                frame.hadEscapingThrow = true;
            }
        }
        if (!locallyCaught) {
            nothrow = false;
            deepestThrowEscapeLevel = -1;
            if (orig != Reachability.Origin.IMMUTABLE) {
                if (orig.unique && orig.uniqueOwner != null) {
                    demoteUniqueOwner(orig.uniqueOwner, Reachability.Origin.IMMUTABLE);
                }
                markStoredOrigin(orig, "throw", ctx);
            }
        }
        return Reachability.Origin.IMMUTABLE;
    }

    Reachability.Origin evalTry(BabbleParser.TryExpressionContext ctx) {
        Map<ParserRuleContext, Reachability.Origin> preEnv = new IdentityHashMap<>(env);
        List<BabbleParser.CatchClauseContext> catches = ctx.catchClause();
        int outerFloor = activeTryStack.size();
        TryFrame frame = new TryFrame(outerFloor, catches, preEnv);

        boolean outerNothrow = nothrow;
        boolean outerHermetic = hermetic;
        boolean outerDeterministic = deterministic;
        int outerEscape = deepestThrowEscapeLevel;
        nothrow = true;
        hermetic = true;
        deterministic = true;
        deepestThrowEscapeLevel = Integer.MAX_VALUE;
        activeTryStack.push(frame);
        Reachability.Origin tryOrig;
        try {
            tryOrig = evalBlock(ctx.tryBlock, false);
        } finally {
            activeTryStack.pop();
        }
        int tryEscape = deepestThrowEscapeLevel;
        boolean tryHermetic = hermetic;
        boolean tryDeterministic = deterministic;
        hermetic = outerHermetic && tryHermetic;
        deterministic = outerDeterministic && tryDeterministic;
        Map<ParserRuleContext, Reachability.Origin> afterTry = env;
        frame.recordUnwindEnv(afterTry, this);
        boolean tryHadNonLocalThrow = !nothrow || frame.hadEscapingThrow;
        boolean tryNothrow =
                nothrow
                        && !frame.hadLocalThrow
                        && !frame.hadEscapingThrow
                        && tryEscape >= outerFloor;

        if (tryNothrow
                && !catches.isEmpty()
                && emitDiagnostics
                && forwardedParams.isEmpty()
                && pseudoForwarded.isEmpty()
                && !hasPriorDiagnostic(ctx)) {
            BabbleParser.CatchClauseContext firstCatch = catches.get(0);
            String warnMsg = "try block cannot throw; catch clause is unreachable";
            String warnKey =
                    firstCatch.getStart().getLine()
                            + ":"
                            + firstCatch.getStart().getCharPositionInLine()
                            + ":WARN:"
                            + warnMsg;
            if (reportedMessages.add(warnKey)) {
                ec.checker.effectWarning(warnMsg, firstCatch);
            }
        }

        Map<ParserRuleContext, Reachability.Origin> catchEntryEnv = frame.unwindEnv;
        Map<ParserRuleContext, Reachability.Origin> finallyEntryEnv =
                mergeEnvs(catchEntryEnv, afterTry);
        Type tryType = ec.checker.rawTypeOf(ctx.tryBlock);
        Map<ParserRuleContext, Reachability.Origin> normalExitEnv =
                tryType != Type.NOTHING ? afterTry : null;
        Reachability.Origin resultOrig =
                tryType != Type.NOTHING ? tryOrig : Reachability.Origin.IMMUTABLE;

        boolean hasExhaustiveCatchAll = false;
        boolean allCatchesNothrow = true;
        int propagatedEscape = (tryEscape < outerFloor) ? tryEscape : Integer.MAX_VALUE;

        for (int i = 0; i < catches.size(); i++) {
            BabbleParser.CatchClauseContext cc = catches.get(i);
            Type catchType = ec.checker.catchTargetType(cc);
            if (cc.type() == null || catchesJavaThrowable(catchType)) {
                hasExhaustiveCatchAll = true;
            }
            env = new IdentityHashMap<>(catchEntryEnv);
            Type boundType = ec.checker.typeOfDeclarationForEffects(cc);
            Reachability.Origin eOrig;
            if (boundType != null && ec.javaResolver.isDeeplyImmutable(boundType)) {
                eOrig = Reachability.Origin.IMMUTABLE;
            } else {
                Reachability.Origin localRouted = frame.caughtOrigins[i];
                if (tryHadNonLocalThrow || localRouted == null) {
                    Reachability.Origin nonLocal =
                            (!tryHermetic || !tryDeterministic)
                                    ? Reachability.Origin.ofExternal(cc, false)
                                    : new Reachability.Origin(
                                            Reachability.Origin.EMPTY_BITS,
                                            false,
                                            false,
                                            Set.of(cc),
                                            new Reachability.PathKey(cc, List.of()));
                    eOrig =
                            localRouted == null
                                    ? nonLocal
                                    : withLocalAliasKey(localRouted.join(nonLocal), cc, boundType);
                } else {
                    eOrig = withLocalAliasKey(localRouted, cc, boundType);
                }
            }
            env.put(cc, eOrig);
            nothrow = true;
            deepestThrowEscapeLevel = Integer.MAX_VALUE;
            Reachability.Origin catchOrig = evalBlock(cc.catchBlock, false);
            int catchEscape = deepestThrowEscapeLevel;
            allCatchesNothrow &= nothrow && catchEscape >= outerFloor;
            if (catchEscape < outerFloor) {
                propagatedEscape = Math.min(propagatedEscape, catchEscape);
            }
            env.remove(cc);
            Map<ParserRuleContext, Reachability.Origin> afterCatch = env;
            finallyEntryEnv = mergeEnvs(finallyEntryEnv, afterCatch);
            Type catchBlockType = ec.checker.rawTypeOf(cc.catchBlock);
            if (catchBlockType != Type.NOTHING) {
                normalExitEnv =
                        normalExitEnv == null ? afterCatch : mergeEnvs(normalExitEnv, afterCatch);
                resultOrig =
                        resultOrig == Reachability.Origin.IMMUTABLE
                                ? catchOrig
                                : resultOrig.join(catchOrig);
            }
        }

        boolean finallyNothrow = true;
        if (ctx.finallyBlock != null) {
            env = new IdentityHashMap<>(finallyEntryEnv);
            nothrow = true;
            deepestThrowEscapeLevel = Integer.MAX_VALUE;
            evalBlock(ctx.finallyBlock, false, true);
            int finEscape = deepestThrowEscapeLevel;
            finallyNothrow = nothrow && finEscape >= outerFloor;
            if (finEscape < outerFloor) {
                propagatedEscape = Math.min(propagatedEscape, finEscape);
            }
            ec.pass.analysis.effects().setTryFinallyNothrow(ctx, finallyNothrow);
        } else {
            env = normalExitEnv != null ? normalExitEnv : preEnv;
        }

        boolean tryExprNothrow =
                tryNothrow
                        ? finallyNothrow
                        : (hasExhaustiveCatchAll && allCatchesNothrow && finallyNothrow);
        nothrow = outerNothrow && tryExprNothrow;
        deepestThrowEscapeLevel = Math.min(outerEscape, propagatedEscape);

        return resultOrig;
    }

    private static boolean catchesJavaThrowable(Type catchType) {
        if (catchType == null || catchType == Type.ERROR) {
            return false;
        }
        for (Type m : TypeChecker.flattenCatchUnion(catchType)) {
            if (m instanceof Type.JavaInstance ji && ji.type() == Throwable.class) {
                return true;
            }
        }
        return false;
    }

    Reachability.Origin evalBooleanOp(BabbleParser.BooleanOpContext ctx) {
        eval(ctx.left);
        Map<ParserRuleContext, Reachability.Origin> before = new IdentityHashMap<>(env);
        eval(ctx.right);
        env = mergeEnvs(before, env);
        return Reachability.Origin.IMMUTABLE;
    }

    Reachability.Origin evalBinaryOp(BabbleParser.BinaryOpContext bin) {
        Reachability.Origin leftOrig = eval(bin.left);
        Reachability.Origin rightOrig = eval(bin.right);
        String op = bin.op.getText();
        Type lt = effectiveType(bin.left);
        Type rt = effectiveType(bin.right);
        Type res = ec.checker.rawTypeOf(bin);
        if (res == Type.ERROR || lt == Type.ERROR || rt == Type.ERROR || hasPriorDiagnostic(bin)) {
            return Reachability.Origin.IMMUTABLE;
        }
        switch (op) {
            case "+" -> {
                if (res == Type.STRING && (lt == Type.STRING || rt == Type.STRING)) {
                    if (lt != Type.STRING) {
                        applyEffects(ec.javaResolver.toStringProfile(lt));
                        if (lt instanceof Type.Array) {
                            recordTargetRead(leftOrig, lt);
                        }
                    }
                    if (rt != Type.STRING) {
                        applyEffects(ec.javaResolver.toStringProfile(rt));
                        if (rt instanceof Type.Array) {
                            recordTargetRead(rightOrig, rt);
                        }
                    }
                }
            }
            case "==", "!=", "<>" -> {
                applyEffects(ec.javaResolver.equalityProfile(lt, rt));
                if (lt instanceof Type.Array) {
                    recordTargetRead(leftOrig, lt);
                }
                if (rt instanceof Type.Array) {
                    recordTargetRead(rightOrig, rt);
                }
            }
            case "<", "<=", ">", ">=" -> applyEffects(ec.javaResolver.comparisonProfile(lt, rt));
            case "mod", "//" -> {
                if (!(lt instanceof Type.Instance) && !isNonZeroIntegerConstant(bin.right)) {
                    nothrow = false;
                }
            }
            default -> {
                // -, *, / on numbers are pure deterministic nothrow (1 / 0 == Infinity).
            }
        }
        return Reachability.Origin.IMMUTABLE;
    }

    Reachability.Origin evalRange(BabbleParser.RangeExpressionContext range) {
        eval(range.left);
        eval(range.right);
        Type res = ec.checker.rawTypeOf(range);
        if (res == Type.ERROR || hasPriorDiagnostic(range)) {
            return Reachability.Origin.IMMUTABLE;
        }
        Type elem = Type.rangeElement(res);
        if (elem != null
                && !Type.isNumericScalar(elem)
                && elem != Type.CHAR
                && elem != Type.STRING) {
            applyEffects(ec.javaResolver.comparisonProfile(elem, elem));
        }
        return ec.javaResolver.isDeeplyImmutable(res)
                ? Reachability.Origin.IMMUTABLE
                : Reachability.Origin.ofUnique(range, null, false);
    }

    static Long tryEvalIntegerConstant(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext b
                && b.block().expression().size() == 1) {
            expr = b.block().expression(0);
        }
        if (expr instanceof BabbleParser.IntegerContext lit) {
            try {
                return Long.parseLong(lit.getText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (expr instanceof BabbleParser.LongLiteralContext lit) {
            String text = lit.getText();
            try {
                return Long.parseLong(text.substring(0, text.length() - 1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (expr instanceof BabbleParser.NegateContext neg) {
            Long inner = tryEvalIntegerConstant(neg.operand);
            return inner != null && inner != Long.MIN_VALUE ? -inner : null;
        }
        return null;
    }

    static boolean isNonZeroIntegerConstant(BabbleParser.ExpressionContext expr) {
        Long v = tryEvalIntegerConstant(expr);
        return v != null && v != 0L;
    }

    static boolean isPositiveIntegerConstant(BabbleParser.ExpressionContext expr) {
        Long v = tryEvalIntegerConstant(expr);
        return v != null && v > 0L;
    }

    Map<ParserRuleContext, Reachability.Origin> mergeEnvs(
            Map<ParserRuleContext, Reachability.Origin> a,
            Map<ParserRuleContext, Reachability.Origin> b) {
        Map<ParserRuleContext, Reachability.Origin> out = new IdentityHashMap<>();
        Set<ParserRuleContext> keys = Collections.newSetFromMap(new IdentityHashMap<>());
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        for (ParserRuleContext k : keys) {
            Reachability.Origin oa = a.get(k);
            Reachability.Origin ob = b.get(k);
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

    private static boolean envsEqual(
            Map<ParserRuleContext, Reachability.Origin> a,
            Map<ParserRuleContext, Reachability.Origin> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<ParserRuleContext, Reachability.Origin> e : a.entrySet()) {
            if (!Objects.equals(e.getValue(), b.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    Map<ParserRuleContext, Reachability.Origin> widenEnvs(
            Map<ParserRuleContext, Reachability.Origin> a,
            Map<ParserRuleContext, Reachability.Origin> b) {
        Map<ParserRuleContext, Reachability.Origin> out = new IdentityHashMap<>();
        Set<ParserRuleContext> keys = Collections.newSetFromMap(new IdentityHashMap<>());
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        for (ParserRuleContext k : keys) {
            Reachability.Origin oa = a.get(k);
            Reachability.Origin ob = b.get(k);
            if (oa == null) {
                out.put(k, ob);
            } else if (ob == null) {
                out.put(k, oa);
            } else {
                out.put(k, oa.widen(ob));
            }
        }
        return out;
    }

    int resolveParamCallbackIndex(BabbleParser.ExpressionContext expr) {
        if (!trackSymbolic || expr == null) {
            return -1;
        }
        BabbleParser.ExpressionContext u = unwrapParens(expr);
        if (u instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            Object resolved =
                    ec.checker.resolveNameForEffects(
                            sel.ID().getText(), ec.checker.occurrenceScopeOf(sel));
            if (resolved instanceof ParserRuleContext decl) {
                Integer idx = paramIndex.get(decl);
                if (idx != null && !forwardedParams.contains(idx)) {
                    return idx;
                }
            }
        }
        return -1;
    }

    void recordCallbackArgFlow(int cbIdx, int slotIdx, Reachability.Origin argOrigin) {
        if (!trackSymbolic || cbIdx < 0 || cbIdx >= symParamFlow.length || slotIdx < 0) {
            return;
        }
        calledCallbacks.set(cbIdx);
        ensureFlowCapacity(cbIdx, slotIdx + 1);
        if (argOrigin == null || argOrigin == Reachability.Origin.IMMUTABLE) {
            return;
        }
        Reachability.Origin flat = argOrigin.flattenElements();
        long mask = 0L;
        for (int p = flat.params.nextSetBit(0);
                p >= 0 && p < 64;
                p = flat.params.nextSetBit(p + 1)) {
            mask |= (1L << p);
        }
        symParamFlow[cbIdx][slotIdx] |= mask;
        if (flat.aliasesThis) {
            symThisFlow[cbIdx][slotIdx] = true;
        }
        if (flat.external) {
            symExtFlow[cbIdx][slotIdx] = true;
        }
    }

    private void ensureFlowCapacity(int cbIdx, int minSlots) {
        if (symParamFlow[cbIdx] == null || symParamFlow[cbIdx].length < minSlots) {
            symParamFlow[cbIdx] =
                    symParamFlow[cbIdx] == null
                            ? new long[minSlots]
                            : Arrays.copyOf(symParamFlow[cbIdx], minSlots);
            symThisFlow[cbIdx] =
                    symThisFlow[cbIdx] == null
                            ? new boolean[minSlots]
                            : Arrays.copyOf(symThisFlow[cbIdx], minSlots);
            symExtFlow[cbIdx] =
                    symExtFlow[cbIdx] == null
                            ? new boolean[minSlots]
                            : Arrays.copyOf(symExtFlow[cbIdx], minSlots);
        }
    }

    SymbolicEffectSummary buildSymbolicSummary(
            EffectChecker.CallableSummary residual, EffectSet[] defaultCbEffects) {
        return new SymbolicEffectSummary(
                residual,
                false,
                residual.effects(),
                residual.mutParams().clone(),
                residual.borrowParams().clone(),
                residual.readParams().clone(),
                residual.readsThis(),
                (BitSet) calledCallbacks.clone(),
                defaultCbEffects,
                symParamFlow,
                symThisFlow,
                symExtFlow);
    }

    /**
     * The type of an expression where it occurs, narrowed by the tests that guard it (as the typing
     * walk recorded it).
     */
    Type effectiveType(BabbleParser.ExpressionContext expr) {
        BabbleParser.ExpressionContext unwrapped = unwrapParens(expr);
        return ec.checker.rawTypeOf(
                unwrapped instanceof BabbleParser.SelectorContext sel && sel.expression() == null
                        ? unwrapped
                        : expr);
    }

    boolean isRedundantCast(Type src, Type dst) {
        if (src == null || dst == null || src == Type.ERROR || dst == Type.ERROR) {
            return true;
        }
        return ec.checker.assignableNoWidening(src, dst);
    }

    void applyEffects(EffectSet callee) {
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

    void applyCallableSummaryEffects(EffectChecker.CallableSummary summary) {
        applyEffects(summary.effects());
        for (Object readOwner : summary.frameReadOwners()) {
            if (readOwner != owner) {
                if (frameChain.contains(readOwner)) {
                    frameReadOwners.add(readOwner);
                } else {
                    deterministic = false;
                }
            }
        }
        for (Object writeOwner : summary.frameWriteOwners()) {
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

    BabbleParser.FunctionLiteralContext enclosingFunctionOfDecl(ParserRuleContext decl) {
        ScopeNode s = ec.pass.definitionScopes.get(decl);
        while (s != null) {
            if (s.kind == ScopeNode.Kind.FUNCTION
                    && s.owner instanceof BabbleParser.FunctionLiteralContext fn) {
                return fn;
            }
            if (s.kind == ScopeNode.Kind.CLASS_BODY
                    || s.kind == ScopeNode.Kind.STATIC_BODY
                    || s.kind == ScopeNode.Kind.INTERFACE_BODY) {
                return null;
            }
            s = s.parent;
        }
        return null;
    }
}
