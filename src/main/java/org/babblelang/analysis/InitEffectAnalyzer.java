package org.babblelang.analysis;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.effects.Effect;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;

/**
 * Effect analysis and verification for class/record/object {@code init} blocks, parameterized
 * constructors, and delegating {@code this(...)} calls ({@code BIP040-D9}).
 */
final class InitEffectAnalyzer {
    private InitEffectAnalyzer() {}

    static boolean isInitOwnedLocal(
            ParserRuleContext owner,
            ParserRuleContext decl,
            BabbleParser.FunctionLiteralContext declFn,
            BabbleParser.FunctionLiteralContext ownerFn) {
        if (!(owner instanceof BabbleParser.InitExpressionContext
                        || owner instanceof BabbleParser.ClassExpressionContext)
                || declFn != ownerFn) {
            return false;
        }
        for (ParserRuleContext p = decl; p != null; p = p.getParent()) {
            if (p == owner) {
                return true;
            }
        }
        return false;
    }

    static Reachability.Origin recordConstructingFieldStore(
            EffectBodyWalker w,
            Reachability.Origin rhs,
            Type valueType,
            String fieldName,
            ParserRuleContext at) {
        w.markStoredIntoTarget(
                Reachability.Origin.ofThis(Reachability.THIS_KEY, false),
                rhs,
                valueType,
                fieldName,
                at);
        return rhs;
    }

    static EffectChecker.CallableSummary initialInitSummary(
            EffectChecker ec, BabbleParser.InitExpressionContext init) {
        ScopeNode initScope = ec.checker.scopeOf(init);
        TypeNode node = initScope != null ? initScope.type : null;
        boolean isValue = node != null && node.kind == ClassShape.Kind.VALUE_CLASS;
        ec.modifiers.parseInitPrefix(init, isValue, true);
        List<BabbleParser.FnParamContext> params = init.fnParameters().fnParam();
        int n = params.size();
        boolean[] mutParams = new boolean[n];
        boolean[] borrowParams = new boolean[n];
        boolean[] uniqueParams = new boolean[n];
        for (int i = 0; i < n; i++) {
            EffectChecker.ParamMods pm = ec.parseParamMods(params.get(i), true);
            if (isValue && pm.mut()) {
                ec.checker.effectDiagnostic(
                        "value class constructor parameter cannot be mut : "
                                + params.get(i).ID().getText(),
                        params.get(i));
            }
            mutParams[i] = !isValue && pm.mut();
            uniqueParams[i] = pm.unique();
            borrowParams[i] = !pm.shared() && !uniqueParams[i];
        }
        EnumSet<Effect> opt = EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW);
        return new EffectChecker.CallableSummary(
                EffectSet.ofInferred(opt, false, true, true, true),
                mutParams,
                borrowParams,
                uniqueParams);
    }

    static EffectChecker.CallableSummary analyzeInitBody(
            EffectChecker ec,
            BabbleParser.InitExpressionContext init,
            EffectChecker.CallableSummary current,
            boolean emitDiagnostics) {
        ScopeNode initScope = ec.checker.scopeOf(init);
        TypeNode node = initScope != null ? initScope.type : null;
        boolean isValue = node != null && node.kind == ClassShape.Kind.VALUE_CLASS;
        TypeNode.Constructor ctor = null;
        if (node != null) {
            for (TypeNode.Constructor c : node.constructors) {
                if (c.initBlock == init) {
                    ctor = c;
                    break;
                }
            }
        }
        List<BabbleParser.FnParamContext> params = init.fnParameters().fnParam();
        boolean[] mutParams = current.mutParams().clone();
        boolean[] borrowParams = current.borrowParams().clone();
        EffectChecker.ParsedPrefix prefix = ec.modifiers.parseInitPrefix(init, isValue, false);
        EffectBodyWalker analyzer =
                new EffectBodyWalker(
                        ec,
                        init,
                        initScope,
                        params,
                        false,
                        mutParams,
                        borrowParams,
                        current.uniqueParams(),
                        true,
                        true,
                        prefix,
                        Set.of(),
                        emitDiagnostics);
        analyzer.constructingSelf = true;
        for (BabbleParser.FnParamContext p : params) {
            if (p.defaultValue != null) {
                analyzer.eval(p.defaultValue);
            }
        }
        if (ctor != null
                && ctor.delegateIndex < 0
                && node.ctx instanceof BabbleParser.ClassExpressionContext cls) {
            analyzer.evalClassBody(cls.classBlock);
        }
        analyzer.evalBlock(init.initBlock, false, true);

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
        return new EffectChecker.CallableSummary(
                nextSet,
                analyzer.mutParams,
                analyzer.borrowParams,
                current.uniqueParams(),
                Set.of(),
                Set.of(),
                false,
                EffectChecker.EMPTY_SUMMARY_BITS,
                false,
                false,
                false,
                (BitSet) analyzer.ctorStoredParams.clone(),
                analyzer.readParams,
                false);
    }

    static void verifyInit(EffectChecker ec, BabbleParser.InitExpressionContext init) {
        ScopeNode initScope = ec.checker.scopeOf(init);
        TypeNode node = initScope != null ? initScope.type : null;
        boolean isValue = node != null && node.kind == ClassShape.Kind.VALUE_CLASS;
        EffectChecker.ParsedPrefix prefix = ec.modifiers.parseInitPrefix(init, isValue, false);
        if (node != null) {
            int ctorIdx = 0;
            for (int i = 0; i < node.constructors.size(); i++) {
                if (node.constructors.get(i).initBlock == init) {
                    ctorIdx = i;
                    break;
                }
            }
            List<Type.Parameter> cParams = ec.checker.constructorParamsForEffects(node, ctorIdx);
            ec.modifiers.validateCallingClause(
                    init.effectPrefix(), ec.modifiers.declaredBorrows(init, cParams));
        }
        EffectChecker.CallableSummary converged = ec.summaries.get(init);
        EffectChecker.CallableSummary verified =
                ec.finalizeSummary(init, ec.recomputeSummary(init, converged, true));
        verifyInitContract(ec, init, node, isValue, prefix, verified);
    }

    static void verifyInitContract(
            EffectChecker ec,
            BabbleParser.InitExpressionContext init,
            TypeNode node,
            boolean isValue,
            EffectChecker.ParsedPrefix prefix,
            EffectChecker.CallableSummary verified) {
        EffectSet actual = verified.effects();
        if (isValue) {
            if (!actual.isPure()) {
                ec.checker.effectDiagnostic(
                        "value class " + node.name + " constructor must be pure", init);
            } else if (!actual.isDeterministic()) {
                ec.checker.effectDiagnostic(
                        "value class " + node.name + " constructor must be deterministic", init);
            }
            if (!actual.borrowThis()) {
                ec.checker.effectDiagnostic("Record init cannot leak this", init);
            }
        } else if (!prefix.io()) {
            if (!actual.isHermetic()) {
                if (prefix.explicitVolatile()) {
                    ec.checker.effectDiagnostic(
                            "Constructor declared volatile violates hermetic guarantee : mark init as io",
                            init);
                } else {
                    ec.checker.effectDiagnostic(
                            "Constructor violates pure guarantee (performs I/O or external mutation) : mark init as io",
                            init);
                }
            } else if (!prefix.explicitVolatile() && !actual.isDeterministic()) {
                ec.checker.effectDiagnostic(
                        "Constructor violates deterministic guarantee : mark init as volatile (or io)",
                        init);
            }
        }
        if (prefix.effects().contains(Effect.NOTHROW) && !actual.isNothrow()) {
            ec.checker.effectDiagnostic(
                    "Constructor declared nothrow violates nothrow guarantee", init);
        }
    }

    static Reachability.Origin evalObjectExpression(
            EffectChecker ec, EffectBodyWalker w, BabbleParser.ObjectExpressionContext obj) {
        boolean prevConstructing = w.constructingSelf;
        BitSet prevCtorStored = (BitSet) w.ctorStoredParams.clone();
        w.constructingSelf = true;
        w.ctorStoredParams.clear();
        boolean objUnique = true;
        Reachability.Origin capturedOrigin = Reachability.Origin.IMMUTABLE;
        try {
            for (BabbleParser.ExpressionContext s : obj.createBlock.expression()) {
                if (s instanceof BabbleParser.DefExpressionContext def) {
                    if (def.value instanceof BabbleParser.FunctionLiteralContext methodLit) {
                        capturedOrigin =
                                joinOrigins(
                                        capturedOrigin,
                                        collectCapturedOrigin(
                                                ec,
                                                w,
                                                methodLit,
                                                "object method " + def.name.getText(),
                                                methodLit));
                        continue;
                    }
                    if (def.value instanceof BabbleParser.OverloadExpressionContext ov) {
                        capturedOrigin =
                                joinOrigins(
                                        capturedOrigin,
                                        collectOverloadCaptures(
                                                ec, w, ov, "object method " + def.name.getText()));
                        continue;
                    }
                    Reachability.Origin init =
                            def.value != null ? w.eval(def.value) : Reachability.Origin.IMMUTABLE;
                    Type defType = ec.checker.typeOfDeclarationForEffects(def);
                    if (!w.isUnattachedUniqueOrImmutable(init, defType)) {
                        objUnique = false;
                        if (init.unique && init.uniqueOwner != null) {
                            w.demoteUniqueOwner(init.uniqueOwner, Reachability.Origin.IMMUTABLE);
                        }
                    }
                    w.markStoredOrigin(init, "field " + def.name.getText(), def);
                } else if (s instanceof BabbleParser.InitExpressionContext initExpr) {
                    EffectChecker.ParsedPrefix initPrefix =
                            ec.modifiers.parseInitPrefix(initExpr, false, w.emitDiagnostics);
                    boolean prevHerm = w.hermetic;
                    boolean prevDet = w.deterministic;
                    boolean prevNothrow = w.nothrow;
                    boolean prevBorrowThis = w.borrowThis;
                    w.hermetic = true;
                    w.deterministic = true;
                    w.nothrow = true;
                    w.borrowThis = true;
                    w.evalBlock(initExpr.initBlock, false, true);
                    boolean initHerm = w.hermetic;
                    boolean initDet = w.deterministic;
                    boolean initNothrow = w.nothrow;
                    boolean initBorrowThis = w.borrowThis;
                    w.hermetic = prevHerm && initHerm;
                    w.deterministic = prevDet && initDet;
                    w.nothrow = prevNothrow && initNothrow;
                    w.borrowThis = prevBorrowThis;
                    if (!initHerm || !initBorrowThis) {
                        objUnique = false;
                    }
                    if (w.emitDiagnostics && !initPrefix.io()) {
                        if (!initHerm) {
                            if (initPrefix.explicitVolatile()) {
                                w.reportOnce(
                                        "Constructor declared volatile violates hermetic guarantee : mark init as io",
                                        initExpr);
                            } else {
                                w.reportOnce(
                                        "Constructor violates pure guarantee (performs I/O or external mutation) : mark init as io",
                                        initExpr);
                            }
                        } else if (!initPrefix.explicitVolatile() && !initDet) {
                            w.reportOnce(
                                    "Constructor violates deterministic guarantee : mark init as volatile (or io)",
                                    initExpr);
                        }
                    }
                    if (w.emitDiagnostics
                            && initPrefix.effects().contains(Effect.NOTHROW)
                            && !initNothrow) {
                        w.reportOnce(
                                "Constructor declared nothrow violates nothrow guarantee",
                                initExpr);
                    }
                } else {
                    w.evalDiscarded(s);
                }
            }
            for (int p = w.ctorStoredParams.nextSetBit(0);
                    p >= 0;
                    p = w.ctorStoredParams.nextSetBit(p + 1)) {
                capturedOrigin =
                        joinOrigins(capturedOrigin, Reachability.Origin.ofParam(p, obj, false));
            }
        } finally {
            w.constructingSelf = prevConstructing;
            w.ctorStoredParams.clear();
            w.ctorStoredParams.or(prevCtorStored);
        }
        boolean imm = ec.javaResolver.isDeeplyImmutable(ec.checker.rawTypeOf(obj));
        if (capturedOrigin != Reachability.Origin.IMMUTABLE) {
            Reachability.Origin base =
                    objUnique
                            ? Reachability.Origin.ofUnique(obj, null, false)
                            : Reachability.Origin.ofExternal(obj, false);
            return base.absorbElement(capturedOrigin);
        }
        return objUnique
                ? Reachability.Origin.ofUnique(obj, null, imm)
                : Reachability.Origin.ofExternal(obj, imm);
    }

    static Reachability.Origin evalClassExpression(
            EffectChecker ec, EffectBodyWalker w, BabbleParser.ClassExpressionContext cls) {
        return collectCapturedOrigin(ec, w, cls, "captured class", cls);
    }

    static Reachability.Origin evalInterfaceExpression(
            EffectChecker ec, EffectBodyWalker w, BabbleParser.InterfaceExpressionContext iface) {
        if (iface.interfaceBlock != null) {
            for (BabbleParser.ExpressionContext stmt : iface.interfaceBlock.expression()) {
                if (stmt instanceof BabbleParser.DefExpressionContext def) {
                    if (def.value instanceof BabbleParser.FunctionLiteralContext methodLit) {
                        Reachability.Origin cap =
                                collectCapturedOrigin(
                                        ec,
                                        w,
                                        methodLit,
                                        "interface method " + def.name.getText(),
                                        methodLit);
                        w.markStoredOrigin(cap, "interface method " + def.name.getText(), def);
                    } else if (def.value instanceof BabbleParser.OverloadExpressionContext ov) {
                        Reachability.Origin cap =
                                collectOverloadCaptures(
                                        ec, w, ov, "interface method " + def.name.getText());
                        w.markStoredOrigin(cap, "interface method " + def.name.getText(), def);
                    }
                }
            }
        }
        return Reachability.Origin.IMMUTABLE;
    }

    private static Reachability.Origin collectOverloadCaptures(
            EffectChecker ec,
            EffectBodyWalker w,
            BabbleParser.OverloadExpressionContext ov,
            String destination) {
        Reachability.Origin acc = Reachability.Origin.IMMUTABLE;
        for (BabbleParser.ExpressionContext arm : ov.expression()) {
            Reachability.Origin armOrig =
                    arm instanceof BabbleParser.FunctionLiteralContext lit
                            ? collectCapturedOrigin(ec, w, lit, destination, lit)
                            : w.eval(arm);
            acc = joinOrigins(acc, armOrig);
        }
        return acc;
    }

    private static Reachability.Origin collectCapturedOrigin(
            EffectChecker ec,
            EffectBodyWalker w,
            ParserRuleContext scopeOwner,
            String destination,
            ParserRuleContext site) {
        Resolution res = ec.pass.analysis.resolution();
        if (res == null || scopeOwner == null) {
            return Reachability.Origin.IMMUTABLE;
        }
        Reachability.Origin captured = Reachability.Origin.IMMUTABLE;
        for (Resolution.Variable cap : res.captured(scopeOwner)) {
            ParserRuleContext decl = cap.declaration();
            if (decl == null) {
                continue;
            }
            Reachability.Origin orig = w.env.get(decl);
            if (orig == null && w.paramIndex.containsKey(decl)) {
                orig =
                        Reachability.Origin.ofParam(
                                w.paramIndex.get(decl),
                                decl,
                                ec.javaResolver.isDeeplyImmutable(
                                        ec.checker.typeOfDeclarationForEffects(decl)));
            } else if (orig == null && w.pseudoForwarded.containsKey(decl)) {
                orig = Reachability.Origin.ofParam(w.pseudoForwarded.get(decl), decl, false);
            }
            if (orig == null) {
                continue;
            }
            if (orig.moved) {
                w.reportOnce("Use of moved variable " + cap.name(), site);
                continue;
            }
            if (orig.unique && orig.uniqueOwner != null) {
                w.demoteUniqueOwner(orig.uniqueOwner, Reachability.Origin.IMMUTABLE);
                orig = w.env.getOrDefault(decl, orig.withoutUnique());
            }
            if (w.paramIndex.containsKey(decl) || w.pseudoForwarded.containsKey(decl)) {
                Reachability.Origin flat = orig.flattenElements();
                Reachability.Origin alias = flat.stripDirectIdentity(false);
                captured = joinOrigins(captured, alias);
            } else {
                w.markStoredOrigin(orig, destination, site);
            }
        }
        return captured;
    }

    /**
     * The origin of a closure value (BIP017-D6) : capturing a parameter or {@code this} aliases it,
     * like returning it does (BIP016-D10), so the closure carries their provenance and escapes only
     * where the closure itself is stored. Captured locals are still stored on capture, their later
     * assignments being outer-variable stores. Any capture of a {@code unique} variable invalidates
     * its uniqueness ({@code BIP032}).
     */
    static Reachability.Origin checkClosureCaptureEscape(
            EffectChecker ec, EffectBodyWalker w, BabbleParser.FunctionLiteralContext inner) {
        boolean frameLocal = ec.frameLocalOwner.get(inner) == w.owner;
        EffectChecker.CallableSummary innerSum = ec.summaries.get(inner);
        Resolution res = ec.pass.analysis.resolution();
        if (res == null) {
            return Reachability.Origin.IMMUTABLE;
        }
        Reachability.Origin closure = Reachability.Origin.IMMUTABLE;
        Set<Reachability.PathKey> capturedPaths = new java.util.HashSet<>();
        if (w.innerContainsThis(inner)) {
            capturedPaths.add(new Reachability.PathKey(Reachability.THIS_KEY, List.of(), true));
            if (!frameLocal) {
                closure =
                        Reachability.Origin.ofThis(Reachability.THIS_KEY, false)
                                .stripDirectIdentity(false);
            }
            if (innerSum != null && innerSum.effects().mutThis() && !w.mutThis) {
                if (w.inferMut) {
                    w.mutThis = true;
                } else {
                    w.reportOnce(
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
            Reachability.Origin orig = w.env.get(decl);
            if (orig == null && w.paramIndex.containsKey(decl)) {
                orig =
                        Reachability.Origin.ofParam(
                                w.paramIndex.get(decl),
                                decl,
                                ec.javaResolver.isDeeplyImmutable(
                                        ec.checker.typeOfDeclarationForEffects(decl)));
            } else if (orig == null && w.pseudoForwarded.containsKey(decl)) {
                orig = Reachability.Origin.ofParam(w.pseudoForwarded.get(decl), decl, false);
            }
            if (orig == null) {
                continue;
            }
            if (orig.moved) {
                w.reportOnce("Use of moved variable " + cap.name(), inner);
                continue;
            }
            if (orig.unique && orig.uniqueOwner != null) {
                if (frameLocal) {
                    w.dropUniqueKeepFresh(orig.uniqueOwner);
                } else {
                    w.demoteUniqueOwner(orig.uniqueOwner, Reachability.Origin.IMMUTABLE);
                }
                orig = w.env.getOrDefault(decl, orig.withoutUnique());
            }
            if (orig != Reachability.Origin.IMMUTABLE) {
                Set<Reachability.PathKey> capPaths = orig.allReachablePaths();
                if (capPaths.isEmpty()) {
                    capturedPaths.add(new Reachability.PathKey(decl, List.of(), true));
                } else {
                    capturedPaths.addAll(capPaths);
                }
            }
            if (frameLocal) {
                continue;
            }
            if (w.paramIndex.containsKey(decl) || w.pseudoForwarded.containsKey(decl)) {
                Reachability.Origin flat = orig.flattenElements();
                Reachability.Origin alias = flat.stripDirectIdentity(false);
                closure = closure == Reachability.Origin.IMMUTABLE ? alias : closure.join(alias);
            } else {
                w.markStoredOrigin(orig, "captured closure", inner);
            }
        }
        collectOuterRefPaths(ec, w, inner, inner, capturedPaths);
        return capturedPaths.isEmpty()
                ? closure
                : closure.withAddedPaths(Set.copyOf(capturedPaths));
    }

    private static void collectOuterRefPaths(
            EffectChecker ec,
            EffectBodyWalker w,
            BabbleParser.FunctionLiteralContext inner,
            org.antlr.v4.runtime.tree.ParseTree tree,
            Set<Reachability.PathKey> out) {
        if (tree == null) {
            return;
        }
        ParserRuleContext refDecl = null;
        if (tree instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            ScopeNode occScope = ec.checker.occurrenceScopeOf(sel);
            Object resolved = ec.checker.resolveNameForEffects(sel.ID().getText(), occScope);
            if (resolved instanceof ParserRuleContext prc) {
                refDecl = prc;
            }
        } else if (tree instanceof BabbleParser.AssignExpressionContext assign
                && ScopeAnalysis.assignReceiver(assign) == null) {
            String name = ScopeAnalysis.assignName(assign);
            if (name != null) {
                ScopeNode occScope = ec.checker.occurrenceScopeOf(assign);
                Object resolved = ec.checker.resolveNameForEffects(name, occScope);
                if (resolved instanceof ParserRuleContext prc) {
                    refDecl = prc;
                }
            }
        }
        if (refDecl != null && !MatchEffectAnalyzer.isInsideSubtree(refDecl, inner)) {
            Reachability.Origin orig = w.env.get(refDecl);
            if (orig != null) {
                if (orig != Reachability.Origin.IMMUTABLE && !orig.moved) {
                    Set<Reachability.PathKey> paths = orig.allReachablePaths();
                    if (paths.isEmpty()) {
                        out.add(new Reachability.PathKey(refDecl, List.of(), true));
                    } else {
                        out.addAll(paths);
                    }
                }
            } else {
                Type declType = ec.checker.typeOfDeclarationForEffects(refDecl);
                if (declType != null
                        && declType != Type.ERROR
                        && !ec.javaResolver.isDeeplyImmutable(declType)
                        && !(declType instanceof Type.ClassValue)
                        && !(declType instanceof Type.JavaClassValue)
                        && !(declType instanceof Type.JavaPackage)
                        && !(declType instanceof Type.Module)) {
                    out.add(new Reachability.PathKey(refDecl, List.of(), true));
                }
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectOuterRefPaths(ec, w, inner, tree.getChild(i), out);
        }
    }

    static String literalIndexSegment(BabbleParser.ExpressionContext[] argExprs) {
        if (argExprs == null || argExprs.length == 0 || argExprs[0] == null) {
            return null;
        }
        BabbleParser.ExpressionContext expr = EffectBodyWalker.unwrapParens(argExprs[0]);
        Long iv = EffectBodyWalker.tryEvalIntegerConstant(expr);
        if (iv != null) {
            return "[lit:i:" + iv + "]";
        }
        if (expr instanceof BabbleParser.StringContext
                || expr instanceof BabbleParser.CharLiteralContext
                || expr instanceof BabbleParser.BooleanContext
                || expr instanceof BabbleParser.NullContext) {
            return "[lit:" + expr.getText() + "]";
        }
        return null;
    }

    private static Reachability.Origin joinOrigins(Reachability.Origin a, Reachability.Origin b) {
        if (a == Reachability.Origin.IMMUTABLE) {
            return b;
        }
        if (b == Reachability.Origin.IMMUTABLE) {
            return a;
        }
        return a.join(b);
    }

    static Reachability.Origin evalDelegatingThisCall(
            EffectChecker ec,
            EffectBodyWalker w,
            CallEffectAnalyzer calls,
            BabbleParser.CallContext cc,
            BabbleParser.ExpressionContext calleeExpr,
            List<BabbleParser.CallParameterContext> args,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins) {
        ScopeNode ts = BlockScopes.thisScope(w.ownerScope);
        TypeNode node = ts != null ? ts.type : null;
        if (node != null) {
            int ctorIdx = ec.checker.resolvedConstructorIndexForEffects(cc);
            EffectChecker.CallableSummary cs = ec.constructorSummary(node, ctorIdx);
            if (cs != null) {
                w.applyEffects(cs.effects());
                if (!cs.effects().borrowThis()) {
                    w.borrowThis = false;
                }
                List<Type.Parameter> cParams =
                        ec.checker.constructorParamsForEffects(node, ctorIdx);
                for (int i = 0; i < args.size(); i++) {
                    int pIdx = ec.checker.parameterIndexForArg(cParams, args, args.get(i));
                    boolean storedInSelf = pIdx >= 0 && cs.ctorStoredParams().get(pIdx);
                    boolean uniqueP =
                            pIdx >= 0 && pIdx < cs.uniqueParams().length && cs.uniqueParams()[pIdx];
                    if (storedInSelf
                            && !uniqueP
                            && !w.isUnattachedUniqueOrImmutable(
                                    argOrigins[i], w.effectiveType(argExprs[i]))) {
                        w.ctorStoredParams.or(argOrigins[i].flattenElements().params);
                    }
                }
                calls.applyBabbleArgEffects(
                        cc,
                        calleeExpr,
                        cParams,
                        cs.mutParams(),
                        cs.borrowParams(),
                        cs.uniqueParams(),
                        null,
                        Reachability.Origin.IMMUTABLE,
                        false,
                        false,
                        true,
                        cs.effects().isHermetic(),
                        args,
                        argExprs,
                        argOrigins);
                applyConstructorCallingEffects(
                        calls, node, ctorIdx, cParams, cc, args, argExprs, argOrigins);
            }
        }
        return Reachability.Origin.IMMUTABLE;
    }

    static Reachability.Origin evalClassConstructorCall(
            EffectChecker ec,
            EffectBodyWalker w,
            CallEffectAnalyzer calls,
            Type.ClassValue cv,
            ParserRuleContext call,
            BabbleParser.ExpressionContext calleeExpr,
            List<BabbleParser.CallParameterContext> args,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins,
            Type resType) {
        int ctorIdx =
                call instanceof BabbleParser.CallContext cc
                        ? ec.checker.resolvedConstructorIndexForEffects(cc)
                        : 0;
        EffectChecker.CallableSummary cs = ec.constructorSummary(cv.node(), ctorIdx);
        boolean imm = ec.javaResolver.isDeeplyImmutable(resType);
        boolean ctorHermetic = cs != null && cs.effects().isHermetic();
        boolean ctorFresh = ctorHermetic && cs.effects().borrowThis();
        Reachability.Origin ctorOrigin =
                ctorFresh
                        ? Reachability.Origin.ofUnique(call, null, imm)
                        : Reachability.Origin.ofExternal(call, imm);
        if (cv.node().ctx instanceof BabbleParser.ClassExpressionContext clsCtx) {
            Reachability.Origin clsCaptured =
                    collectCapturedOrigin(ec, w, clsCtx, "captured class", call);
            if (clsCaptured != Reachability.Origin.IMMUTABLE) {
                ctorOrigin =
                        ctorOrigin == Reachability.Origin.IMMUTABLE
                                ? Reachability.Origin.ofUnique(call, null, false)
                                        .absorbElement(clsCaptured)
                                : ctorOrigin.absorbElement(clsCaptured);
            }
        }
        if (cs != null) {
            w.applyEffects(cs.effects());
            List<Type.Parameter> cParams =
                    ec.checker.constructorParamsForEffects(cv.node(), ctorIdx);
            boolean directPrimaryFields = isDirectPrimaryFieldConstructor(cv.node(), ctorIdx);
            for (int i = 0; i < args.size(); i++) {
                int pIdx = ec.checker.parameterIndexForArg(cParams, args, args.get(i));
                boolean storedInSelf = pIdx >= 0 && cs.ctorStoredParams().get(pIdx);
                boolean borrowed =
                        pIdx >= 0 && pIdx < cs.borrowParams().length && cs.borrowParams()[pIdx];
                boolean uniqueP =
                        pIdx >= 0 && pIdx < cs.uniqueParams().length && cs.uniqueParams()[pIdx];
                if ((storedInSelf || !borrowed)
                        && !uniqueP
                        && !w.isUnattachedUniqueOrImmutable(
                                argOrigins[i], w.effectiveType(argExprs[i]))) {
                    if (argOrigins[i].owned() && argOrigins[i].uniqueOwner != null) {
                        w.demoteUniqueOwner(
                                argOrigins[i].uniqueOwner, Reachability.Origin.IMMUTABLE);
                    }
                    if (!imm) {
                        String fieldName =
                                (directPrimaryFields && pIdx >= 0 && pIdx < cParams.size())
                                        ? cParams.get(pIdx).name()
                                        : Reachability.ANY_FIELD;
                        ctorOrigin =
                                ctorFresh
                                        ? ctorOrigin.absorbField(fieldName, argOrigins[i])
                                        : ctorOrigin.withFieldPaths(fieldName, argOrigins[i]);
                    }
                }
            }
            calls.applyBabbleArgEffects(
                    call,
                    calleeExpr,
                    cParams,
                    cs.mutParams(),
                    cs.borrowParams(),
                    cs.uniqueParams(),
                    null,
                    Reachability.Origin.IMMUTABLE,
                    false,
                    false,
                    true,
                    ctorHermetic,
                    args,
                    argExprs,
                    argOrigins);
            applyConstructorCallingEffects(
                    calls, cv.node(), ctorIdx, cParams, call, args, argExprs, argOrigins);
        }
        return ctorOrigin;
    }

    private static boolean isDirectPrimaryFieldConstructor(TypeNode node, int ctorIdx) {
        if (node == null
                || ctorIdx != 0
                || !(node.ctx instanceof BabbleParser.ClassExpressionContext cls)) {
            return false;
        }
        if (cls.classBlock == null) {
            return true;
        }
        for (BabbleParser.ExpressionContext stmt : cls.classBlock.expression()) {
            if (stmt instanceof BabbleParser.DefExpressionContext def
                    && (def.value instanceof BabbleParser.FunctionLiteralContext
                            || def.value instanceof BabbleParser.OverloadExpressionContext)) {
                continue;
            }
            return false;
        }
        return true;
    }

    static boolean isDirectThisExpr(BabbleParser.ExpressionContext expr) {
        return EffectBodyWalker.unwrapParens(expr) instanceof BabbleParser.ThisExpressionContext;
    }

    private static void applyConstructorCallingEffects(
            CallEffectAnalyzer calls,
            TypeNode node,
            int ctorIdx,
            List<Type.Parameter> cParams,
            ParserRuleContext call,
            List<BabbleParser.CallParameterContext> args,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins) {
        if (node == null || ctorIdx < 0 || ctorIdx >= node.constructors.size()) {
            return;
        }
        TypeNode.Constructor ctor = node.constructors.get(ctorIdx);
        if (ctor.initBlock == null || ctor.initBlock.effectPrefix() == null) {
            return;
        }
        Set<Integer> forwarded =
                TypeAnnotations.resolveForwardedParams(ctor.initBlock.effectPrefix(), cParams);
        if (forwarded.isEmpty()) {
            return;
        }
        Type.Function synthetic =
                new Type.Function(
                        List.of(), cParams, Type.ANY, EffectSet.EMPTY, forwarded, Set.of());
        calls.applyForwardedCallbackEffects(
                call, synthetic, null, Reachability.Origin.IMMUTABLE, args, argExprs, argOrigins);
    }
}
