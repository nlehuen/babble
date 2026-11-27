package org.babblelang.analysis;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.effects.Effect;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.JavaTypes;

/**
 * Call-site effect propagation, static {@code mut} exclusivity checking, and bounded higher-order
 * specialization extracted from {@link EffectChecker} ({@code BIP027-I3}).
 */
final class CallEffectAnalyzer {
    private final EffectChecker ec;
    private final EffectBodyWalker w;

    CallEffectAnalyzer(EffectChecker ec, EffectBodyWalker w) {
        this.ec = ec;
        this.w = w;
    }

    Reachability.Origin evalCall(BabbleParser.CallContext call) {
        return evalCallLike(call, call.expression(), call.callParameters().callParameter());
    }

    private Reachability.Origin evalCallLike(
            ParserRuleContext call,
            BabbleParser.ExpressionContext calleeExpr,
            List<BabbleParser.CallParameterContext> args) {
        if (w.effectiveType(calleeExpr) instanceof Type.CoreCollection cc) {
            return evalCoreCollectionCall(cc.kind(), call, args);
        }
        if (w.effectiveType(calleeExpr) instanceof Type.ArrayOfFactory) {
            return evalArrayOfCall(call, args);
        }
        BabbleParser.ExpressionContext unwrappedCallee =
                calleeExpr instanceof BabbleParser.TypeApplyContext ta
                        ? ta.expression()
                        : calleeExpr;
        // Evaluate receiver / callee expression and arguments!
        Reachability.Origin recvOrig = Reachability.Origin.IMMUTABLE;
        BabbleParser.ExpressionContext receiverExpr = null;
        Type arrayRecvType = null;
        Type.Array arrayRecv = null;
        String arrayMethodName = null;
        Type rangeElemType = null;
        String rangeMethodName = null;
        if (unwrappedCallee instanceof BabbleParser.SelectorContext sel
                && sel.expression() != null) {
            receiverExpr = sel.expression();
            Type recvType = w.effectiveType(receiverExpr);
            Type unwrappedRecv = TypeAlgebra.unwrapFrozen(recvType);
            if (unwrappedRecv instanceof Type.Array arr) {
                String mName = ScopeAnalysis.selectorName(sel);
                if (isArrayProtocolMethod(mName)) {
                    arrayRecvType = recvType;
                    arrayRecv = arr;
                    arrayMethodName = mName;
                }
            } else if (Type.rangeElement(unwrappedRecv) != null) {
                rangeElemType = Type.rangeElement(unwrappedRecv);
                rangeMethodName = ScopeAnalysis.selectorName(sel);
            }
            if (!(recvType instanceof Type.JavaPackage || recvType instanceof Type.Module)) {
                boolean preserveSizeDet =
                        "size".equals(arrayMethodName) && isNonReassignedBareBinding(receiverExpr);
                boolean detBefore = w.deterministic;
                recvOrig = w.eval(receiverExpr);
                if (preserveSizeDet) {
                    w.deterministic = detBefore;
                }
            }
        } else {
            w.eval(calleeExpr);
        }

        Reachability.Origin[] argOrigins = new Reachability.Origin[args.size()];
        BabbleParser.ExpressionContext[] argExprs = new BabbleParser.ExpressionContext[args.size()];
        for (int i = 0; i < args.size(); i++) {
            argExprs[i] = args.get(i).expression();
            argOrigins[i] = w.eval(argExprs[i]);
            Type argType = w.effectiveType(argExprs[i]);
            for (Type m : Type.membersOf(argType)) {
                if (m instanceof Type.Function fn) {
                    BabbleParser.FunctionLiteralContext lit = ec.checker.literalOf(fn);
                    EffectChecker.CallableSummary innerSum =
                            lit != null ? ec.summaries.get(lit) : null;
                    if (innerSum != null && !innerSum.frameWriteOwners().isEmpty()) {
                        w.stmtHasSideEffect = true;
                    }
                }
            }
        }

        Type resType = ec.checker.rawTypeOf(call);
        if (arrayMethodName != null) {
            return evalArrayMethodCall(
                    call,
                    receiverExpr,
                    recvOrig,
                    arrayRecvType,
                    arrayRecv,
                    arrayMethodName,
                    args,
                    argExprs,
                    argOrigins,
                    resType);
        }
        if (rangeMethodName != null) {
            return evalRangeMethodCall(call, rangeElemType, rangeMethodName, argExprs, resType);
        }
        Type calleeType = resolveCalleeType(calleeExpr);

        // 1. Built-in `import` / scalar conversions
        if (calleeExpr instanceof BabbleParser.CoreScalarRefContext) {
            return evalCoreConversionCall(resType, argExprs);
        }
        if (isImportCall(calleeExpr)) {
            w.hermetic = false;
            w.deterministic = false;
            w.nothrow = false;
            w.terminating = false;
            return Reachability.Origin.ofExternal(call, ec.javaResolver.isDeeplyImmutable(resType));
        }
        if (calleeType == OperatorTypes.DISCARD_FUNCTION) {
            if (w.constructingSelf
                    && argExprs.length == 1
                    && InitEffectAnalyzer.isDirectThisExpr(argExprs[0])) {
                w.reportOnce("Cannot move or consume this inside init", argExprs[0]);
            }
            w.stmtHasSideEffect = true;
            return Reachability.Origin.IMMUTABLE;
        }
        if (calleeType == OperatorTypes.MOVE_FUNCTION) {
            if (argExprs.length != 1) {
                return Reachability.Origin.IMMUTABLE;
            }
            if (w.constructingSelf && InitEffectAnalyzer.isDirectThisExpr(argExprs[0])) {
                w.reportOnce("Cannot move or consume this inside init", argExprs[0]);
                return Reachability.Origin.IMMUTABLE;
            }
            ParserRuleContext decl = w.resolveOwnedLocalDecl(argExprs[0]);
            Reachability.Origin orig = argOrigins[0];
            if (decl == null) {
                w.reportOnce(
                        "Cannot move expression : target must be a unique local variable",
                        argExprs[0]);
                return Reachability.Origin.ofExternal(
                        call, ec.javaResolver.isDeeplyImmutable(resType));
            }
            if (orig.moved) {
                return Reachability.Origin.ofUnique(
                        call, null, ec.javaResolver.isDeeplyImmutable(resType));
            }
            if (w.paramIndex.containsKey(decl) && w.borrowParams[w.paramIndex.get(decl)]) {
                w.reportOnce(
                        "Cannot move borrowed parameter " + argExprs[0].getText(), argExprs[0]);
                return Reachability.Origin.ofExternal(
                        call, ec.javaResolver.isDeeplyImmutable(resType));
            }
            if (!orig.unique) {
                w.reportOnce(
                        "Cannot move " + argExprs[0].getText() + " : variable is not unique",
                        argExprs[0]);
                return Reachability.Origin.ofExternal(
                        call, ec.javaResolver.isDeeplyImmutable(resType));
            }
            w.env.put(decl, Reachability.Origin.MOVED);
            return Reachability.Origin.ofUnique(
                    call, null, ec.javaResolver.isDeeplyImmutable(resType));
        }
        if (calleeType == OperatorTypes.FREEZE_FUNCTION) {
            if (argExprs.length != 1) {
                return Reachability.Origin.IMMUTABLE;
            }
            if (w.constructingSelf && InitEffectAnalyzer.isDirectThisExpr(argExprs[0])) {
                w.reportOnce("Cannot move or consume this inside init", argExprs[0]);
                return Reachability.Origin.IMMUTABLE;
            }
            Reachability.Origin orig = argOrigins[0];
            Type argType = w.effectiveType(argExprs[0]);
            if (!orig.moved) {
                if (orig.unique) {
                    if (orig.uniqueOwner instanceof ParserRuleContext decl) {
                        if (w.paramIndex.containsKey(decl)
                                && w.borrowParams[w.paramIndex.get(decl)]) {
                            w.reportOnce(
                                    "Cannot freeze borrowed parameter " + argExprs[0].getText(),
                                    argExprs[0]);
                        } else {
                            w.env.put(decl, Reachability.Origin.MOVED);
                        }
                    }
                } else if (!ec.javaResolver.isDeeplyImmutable(argType)
                        && orig != Reachability.Origin.IMMUTABLE
                        && argType != Type.ERROR) {
                    w.reportOnce(
                            "Cannot freeze expression : value is not unique or deeply immutable",
                            argExprs[0]);
                }
            }
            return Reachability.Origin.IMMUTABLE;
        }

        // 2. Java method call (fast-path or dynamic reflective overload set)
        Method rm = ec.checker.resolvedJavaMethodOf(call);
        if (rm != null) {
            EffectChecker.JavaCallProfile profile =
                    ec.javaResolver.resolveJavaMethodProfile(
                            rm.getDeclaringClass(), rm.getName(), rm);
            BabbleParser.ExpressionContext instanceRecv =
                    Modifier.isStatic(rm.getModifiers()) ? null : receiverExpr;
            return applyJavaCall(
                    call,
                    calleeExpr,
                    profile,
                    rm.getDeclaringClass(),
                    rm.getName(),
                    rm.getParameterTypes(),
                    rm.getGenericParameterTypes(),
                    ec.checker.isResolvedJavaVarArgs(call),
                    instanceRecv,
                    recvOrig,
                    argExprs,
                    argOrigins,
                    resType);
        }
        if (calleeType instanceof Type.JavaMethod jm) {
            EffectChecker.JavaCallProfile profile =
                    ec.javaResolver.resolveJavaMethodProfile(jm.owner(), jm.name(), null);
            Class<?>[] samTypes = findOverloadSamTypes(jm.owner(), jm.name(), args.size());
            boolean isClassRecv =
                    receiverExpr != null
                            && ec.checker.rawTypeOf(receiverExpr) instanceof Type.JavaClassValue;
            return applyJavaCall(
                    call,
                    calleeExpr,
                    profile,
                    jm.owner(),
                    jm.name(),
                    samTypes,
                    samTypes,
                    false,
                    isClassRecv ? null : receiverExpr,
                    recvOrig,
                    argExprs,
                    argOrigins,
                    resType);
        }

        // 3. Java constructor call or Java functional interface SAM conversion `Predicate(fn)`
        Constructor<?> rc = ec.checker.resolvedJavaConstructorOf(call);
        if (rc != null) {
            EffectChecker.JavaCallProfile profile =
                    ec.javaResolver.resolveJavaConstructorProfile(rc.getDeclaringClass(), rc);
            return applyJavaCall(
                    call,
                    calleeExpr,
                    profile,
                    rc.getDeclaringClass(),
                    "<init>",
                    rc.getParameterTypes(),
                    rc.getGenericParameterTypes(),
                    ec.checker.isResolvedJavaVarArgs(call),
                    null,
                    Reachability.Origin.IMMUTABLE,
                    argExprs,
                    argOrigins,
                    resType);
        }
        if (calleeType instanceof Type.JavaClassValue jcv) {
            if (jcv.type().isInterface()
                    && args.size() == 1
                    && JavaTypes.isFunctionalInterface(jcv.type())) {
                // Explicit SAM wrapper `Function(fn)` is pure w.deterministic w.nothrow itself
                // (constructing proxy).
                return argOrigins[0];
            }
            EffectChecker.JavaCallProfile profile =
                    ec.javaResolver.resolveJavaConstructorProfile(jcv.type(), null);
            return applyJavaCall(
                    call,
                    calleeExpr,
                    profile,
                    jcv.type(),
                    "<init>",
                    null,
                    null,
                    false,
                    null,
                    Reachability.Origin.IMMUTABLE,
                    argExprs,
                    argOrigins,
                    resType);
        }

        // 4a. Delegating constructor call `this(...)` inside `init` (BIP040-D5)
        if (call instanceof BabbleParser.CallContext cc
                && ec.pass.analysis.calls().isDelegatingThisCall(cc)) {
            return InitEffectAnalyzer.evalDelegatingThisCall(
                    ec, w, this, cc, calleeExpr, args, argExprs, argOrigins);
        }

        // 4. Babble class constructor call `Point(1, 2)`
        if (calleeType instanceof Type.ClassValue cv && !cv.node().isInterface()) {
            return InitEffectAnalyzer.evalClassConstructorCall(
                    ec, w, this, cv, call, calleeExpr, args, argExprs, argOrigins, resType);
        }

        // 5. Babble `recurse(...)`
        if (calleeExpr instanceof BabbleParser.RecurseContext
                && w.owner instanceof BabbleParser.FunctionLiteralContext selfFn) {
            EffectChecker.CallableSummary selfSum =
                    ec.summaries.getOrDefault(selfFn, ec.initialSummary(selfFn));
            w.applyCallableSummaryEffects(selfSum);
            Type selfRaw = ec.checker.rawTypeOf(selfFn);
            List<Type.Parameter> fParams =
                    selfRaw instanceof Type.Function tf ? tf.parameters() : List.of();
            boolean hasRecv = EffectModifiers.canReferenceThis(w.ownerScope);
            Reachability.Origin selfRecv =
                    hasRecv
                            ? Reachability.Origin.ofThis(Reachability.THIS_KEY, false)
                            : Reachability.Origin.IMMUTABLE;
            boolean[] mutableCopiedSpread = new boolean[args.size()];
            Reachability.Origin[] postSpreadOrigins =
                    applyBabbleArgEffects(
                            call,
                            calleeExpr,
                            fParams,
                            selfSum.mutParams(),
                            selfSum.borrowParams(),
                            selfSum.uniqueParams(),
                            null,
                            selfRecv,
                            selfSum.effects().mutThis(),
                            selfSum.deepMutThis(),
                            selfSum.effects().borrowThis(),
                            selfSum.effects().isHermetic(),
                            args,
                            argExprs,
                            argOrigins,
                            null,
                            mutableCopiedSpread);
            if (selfSum.effects().uniqueResult()) {
                return Reachability.Origin.ofUnique(
                        call, null, ec.javaResolver.isDeeplyImmutable(resType));
            }
            return deriveCallReturnOrigin(
                    call,
                    resType,
                    selfSum,
                    hasRecv,
                    selfRecv,
                    argOrigins,
                    postSpreadOrigins,
                    mutableCopiedSpread,
                    fParams,
                    args);
        }

        // 6. Babble function / method / overload set / union of functions
        if (calleeType instanceof Type.Overloaded) {
            TypeChecker.ResolvedOverloadCall resolved =
                    call instanceof BabbleParser.CallContext cc
                            ? ec.checker.resolvedOverloadCalls().get(cc)
                            : null;
            if (resolved != null) {
                if (!resolved.isSplit() && resolved.altType() != null) {
                    return applyBabbleCallableType(
                            call,
                            calleeExpr,
                            resolved.altType(),
                            receiverExpr,
                            recvOrig,
                            args,
                            argExprs,
                            argOrigins,
                            resType);
                }
                if (resolved.isSplit()) {
                    Reachability.Origin combinedOut = Reachability.Origin.IMMUTABLE;
                    Map<ParserRuleContext, Reachability.Origin> initialEnv =
                            new IdentityHashMap<>(w.env);
                    Map<ParserRuleContext, Reachability.Origin> mergedEnv = null;
                    for (TypeChecker.ResolvedSplitBranch b : resolved.splitBranches()) {
                        w.env = new IdentityHashMap<>(initialEnv);
                        Reachability.Origin branchOut =
                                applyBabbleCallableMember(
                                        call,
                                        calleeExpr,
                                        b.altType(),
                                        b.spreadAdapts(),
                                        receiverExpr,
                                        recvOrig,
                                        args,
                                        argExprs,
                                        argOrigins,
                                        resType);
                        combinedOut =
                                combinedOut == Reachability.Origin.IMMUTABLE
                                        ? branchOut
                                        : combinedOut.join(branchOut);
                        mergedEnv =
                                mergedEnv == null
                                        ? new IdentityHashMap<>(w.env)
                                        : w.mergeEnvs(mergedEnv, w.env);
                    }
                    if (mergedEnv != null) {
                        w.env = mergedEnv;
                    }
                    return combinedOut;
                }
            }
        }
        if (calleeType instanceof Type.Function || calleeType instanceof Type.Union) {
            return applyBabbleCallableType(
                    call,
                    calleeExpr,
                    calleeType,
                    receiverExpr,
                    recvOrig,
                    args,
                    argExprs,
                    argOrigins,
                    resType);
        }

        // Fallback (unresolved error type)
        return Reachability.Origin.IMMUTABLE;
    }

    private Reachability.Origin evalCoreConversionCall(
            Type target, BabbleParser.ExpressionContext[] argExprs) {
        if (argExprs.length != 1) {
            return Reachability.Origin.IMMUTABLE;
        }
        Type src = w.effectiveType(argExprs[0]);
        if (target == Type.STRING) {
            w.applyEffects(ec.javaResolver.toStringProfile(src));
            return Reachability.Origin.IMMUTABLE;
        }
        boolean isTotalNumericWidening =
                Type.isNumericScalar(src)
                        && Type.isNumericScalar(target)
                        && Type.numericRank(src) <= Type.numericRank(target);
        boolean isTotalCharWidening =
                src == Type.CHAR
                        && (target == Type.INT
                                || target == Type.LONG
                                || target == Type.FLOAT
                                || target == Type.DOUBLE);
        if (target != src && !isTotalNumericWidening && !isTotalCharWidening) {
            w.nothrow = false;
        }
        return Reachability.Origin.IMMUTABLE;
    }

    private Reachability.Origin evalCoreCollectionCall(
            String kind,
            ParserRuleContext ccc,
            List<BabbleParser.CallParameterContext> paramsList) {
        BabbleParser.ExpressionContext[] argExprs =
                new BabbleParser.ExpressionContext[paramsList.size()];
        Reachability.Origin[] argOrigins = new Reachability.Origin[paramsList.size()];
        for (int i = 0; i < paramsList.size(); i++) {
            argExprs[i] = paramsList.get(i).expression();
            argOrigins[i] = w.eval(argExprs[i]);
        }
        if ("entry".equals(kind)) {
            boolean immutableEntry = true;
            boolean uniqueEntry = true;
            Reachability.Origin freshEntry = Reachability.Origin.ofUnique(ccc, null, false);
            for (int i = 0; i < argExprs.length; i++) {
                Type argType = w.effectiveType(argExprs[i]);
                if (!ec.javaResolver.isDeeplyImmutable(argType)) {
                    immutableEntry = false;
                    if (!w.isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                        uniqueEntry = false;
                        freshEntry = freshEntry.absorbElement(argOrigins[i]);
                        if (argOrigins[i].unique && argOrigins[i].uniqueOwner != null) {
                            w.demoteUniqueOwner(
                                    argOrigins[i].uniqueOwner, Reachability.Origin.IMMUTABLE);
                        }
                    }
                    w.markStoredOrigin(argOrigins[i], "entry", argExprs[i]);
                }
            }
            if (!immutableEntry) {
                w.applyEffects(
                        EffectSet.ofInferred(
                                EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                                false,
                                true,
                                false,
                                true));
                return uniqueEntry ? Reachability.Origin.ofUnique(ccc, null, false) : freshEntry;
            }
            return Reachability.Origin.IMMUTABLE;
        }
        if ("array".equals(kind)) {
            w.applyEffects(
                    EffectSet.ofInferred(
                            EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC),
                            false,
                            true,
                            false,
                            true));
            w.nothrow = false;
            boolean staysUnique = true;
            Reachability.Origin freshArr = Reachability.Origin.ofUnique(ccc, null, false);
            for (int i = 0; i < paramsList.size(); i++) {
                BabbleParser.CallParameterContext p = paramsList.get(i);
                String name = p.memberName() != null ? p.memberName().getText() : null;
                if ("from".equals(name)) {
                    Type rawFrom = w.effectiveType(argExprs[i]);
                    w.recordTargetRead(argOrigins[i], rawFrom);
                    Type fromType = rawFrom instanceof Type.Frozen f ? f.of() : rawFrom;
                    if (fromType instanceof Type.Array arr) {
                        staysUnique = ec.javaResolver.isDeeplyImmutable(arr.element());
                    } else if (fromType instanceof Type.JavaInstance ji) {
                        EffectChecker.JavaCallProfile iterProf =
                                ec.javaResolver.resolveJavaMethodProfile(
                                        ji.type(),
                                        "iterator",
                                        JavaEffectResolver.lookupPublicMethod(
                                                ji.type(), "iterator"));
                        if (!iterProf.effects().isHermetic()) {
                            w.terminating = false;
                        }
                        if (!ji.typeArguments().isEmpty()) {
                            for (Type ta : ji.typeArguments()) {
                                if (!ec.javaResolver.isDeeplyImmutable(ta)) {
                                    staysUnique = false;
                                }
                            }
                        } else {
                            staysUnique = false;
                        }
                    } else {
                        staysUnique = false;
                        w.terminating = false;
                    }
                    if (!staysUnique) {
                        freshArr = freshArr.absorbElement(argOrigins[i]);
                    }
                }
            }
            return staysUnique ? Reachability.Origin.ofUnique(ccc, null, false) : freshArr;
        }

        // list, set, sortedset, map, sortedmap: w.hermetic w.deterministic allocation (unique per
        // BIP007-D6!)
        w.applyEffects(
                EffectSet.ofInferred(
                        EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                        false,
                        true,
                        false,
                        true));
        boolean hasCapacity = false;
        boolean hasFrom = false;
        boolean hasComparator = false;
        boolean hasElements = false;
        boolean staysUnique = true;
        Reachability.Origin freshCol = Reachability.Origin.ofUnique(ccc, null, false);
        for (int i = 0; i < paramsList.size(); i++) {
            BabbleParser.CallParameterContext p = paramsList.get(i);
            String name = p.memberName() != null ? p.memberName().getText() : null;
            if ("capacity".equals(name)) {
                hasCapacity = true;
            } else if ("from".equals(name)) {
                hasFrom = true;
                // `from:` reads and copies elements from the source collection; result carries
                // elements' provenance (BIP019 §4.1, §4.3).
                Type rawFrom = w.effectiveType(argExprs[i]);
                w.recordTargetRead(argOrigins[i], rawFrom);
                Type fromType = rawFrom;
                if (fromType instanceof Type.Frozen f) {
                    fromType = f.of();
                }
                boolean fromImm = true;
                if (fromType instanceof Type.JavaInstance ji && !ji.typeArguments().isEmpty()) {
                    for (Type ta : ji.typeArguments()) {
                        if (!ec.javaResolver.isDeeplyImmutable(ta)) {
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
                w.markStoredOrigin(argOrigins[i], kind + " comparator", argExprs[i]);
                Type cmpType = w.effectiveType(argExprs[i]);
                propagateSamCallbackEffects(cmpType, java.util.Comparator.class);
                if (!(cmpType instanceof Type.Function cmpFn)
                        || !cmpFn.effects().isPure()
                        || !cmpFn.effects().isDeterministic()) {
                    staysUnique = false;
                    freshCol = freshCol.absorbElement(argOrigins[i]);
                }
            } else {
                hasElements = true;
                Type argType = w.effectiveType(argExprs[i]);
                if (!ec.javaResolver.isDeeplyImmutable(argType)) {
                    if (!w.isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                        staysUnique = false;
                        freshCol = freshCol.absorbElement(argOrigins[i]);
                        if (argOrigins[i].unique && argOrigins[i].uniqueOwner != null) {
                            w.demoteUniqueOwner(
                                    argOrigins[i].uniqueOwner, Reachability.Origin.IMMUTABLE);
                        }
                    }
                    w.markStoredOrigin(argOrigins[i], kind, argExprs[i]);
                }
                if ("set".equals(kind)) {
                    w.applyEffects(ec.javaResolver.equalityProfile(argType, argType));
                } else if ("map".equals(kind)) {
                    if (argType instanceof Type.JavaInstance ji
                            && java.util.Map.Entry.class.isAssignableFrom(ji.type())
                            && !ji.typeArguments().isEmpty()) {
                        Type keyType = ji.typeArguments().get(0);
                        w.applyEffects(ec.javaResolver.equalityProfile(keyType, keyType));
                    }
                } else if ("sortedset".equals(kind) && !hasComparator) {
                    w.applyEffects(ec.javaResolver.comparisonProfile(argType, argType));
                } else if ("sortedmap".equals(kind) && !hasComparator) {
                    if (argType instanceof Type.JavaInstance ji
                            && java.util.Map.Entry.class.isAssignableFrom(ji.type())
                            && !ji.typeArguments().isEmpty()) {
                        Type keyType = ji.typeArguments().get(0);
                        w.applyEffects(ec.javaResolver.comparisonProfile(keyType, keyType));
                    }
                }
            }
        }
        if (hasCapacity
                || ((hasFrom || hasElements)
                        && ("sortedset".equals(kind) || "sortedmap".equals(kind)))) {
            w.nothrow = false;
        }
        return staysUnique ? Reachability.Origin.ofUnique(ccc, null, false) : freshCol;
    }

    private static boolean isArrayProtocolMethod(String name) {
        return "size".equals(name)
                || "get".equals(name)
                || "set".equals(name)
                || "equals".equals(name)
                || "hashCode".equals(name)
                || "toString".equals(name);
    }

    private boolean isNonReassignedBareBinding(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext b
                && b.block().expression().size() == 1) {
            expr = b.block().expression(0);
        }
        if (expr instanceof BabbleParser.SelectorContext rsel && rsel.expression() == null) {
            Object resolved =
                    ec.checker.resolveNameForEffects(
                            rsel.ID().getText(), ec.checker.occurrenceScopeOf(rsel));
            return resolved instanceof ParserRuleContext decl && !ec.checker.isReassigned(decl);
        }
        return false;
    }

    private Reachability.Origin evalArrayOfCall(
            ParserRuleContext call, List<BabbleParser.CallParameterContext> paramsList) {
        BabbleParser.ExpressionContext[] argExprs =
                new BabbleParser.ExpressionContext[paramsList.size()];
        Reachability.Origin[] argOrigins = new Reachability.Origin[paramsList.size()];
        for (int i = 0; i < paramsList.size(); i++) {
            argExprs[i] = paramsList.get(i).expression();
            argOrigins[i] = w.eval(argExprs[i]);
        }
        w.applyEffects(
                EffectSet.ofInferred(
                        EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                        false,
                        true,
                        false,
                        true));
        boolean staysUnique = true;
        Reachability.Origin freshArr = Reachability.Origin.ofUnique(call, null, false);
        for (int i = 0; i < argExprs.length; i++) {
            Type argType = w.effectiveType(argExprs[i]);
            if (!ec.javaResolver.isDeeplyImmutable(argType)) {
                if (!w.isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                    staysUnique = false;
                    freshArr = freshArr.absorbElement(argOrigins[i]);
                    if (argOrigins[i].unique && argOrigins[i].uniqueOwner != null) {
                        w.demoteUniqueOwner(
                                argOrigins[i].uniqueOwner, Reachability.Origin.IMMUTABLE);
                    }
                }
                w.markStoredOrigin(argOrigins[i], "array", argExprs[i]);
            }
        }
        return staysUnique ? Reachability.Origin.ofUnique(call, null, false) : freshArr;
    }

    private Reachability.Origin evalArrayMethodCall(
            ParserRuleContext call,
            BabbleParser.ExpressionContext receiverExpr,
            Reachability.Origin recvOrig,
            Type recvType,
            Type.Array arr,
            String methodName,
            List<BabbleParser.CallParameterContext> args,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins,
            Type resType) {
        if (resType == Type.ERROR) {
            return Reachability.Origin.IMMUTABLE;
        }
        return switch (methodName) {
            case "size" -> Reachability.Origin.IMMUTABLE;
            case "get" -> {
                w.nothrow = false;
                w.recordTargetRead(recvOrig, recvType);
                if (ec.javaResolver.isDeeplyImmutable(resType)) {
                    yield Reachability.Origin.IMMUTABLE;
                }
                Reachability.Origin flat = recvOrig.flattenElements();
                yield new Reachability.Origin(
                        flat.params,
                        flat.aliasesThis,
                        flat.external,
                        false,
                        null,
                        false,
                        null,
                        Set.of(call),
                        new Reachability.PathKey(call, List.of()));
            }
            case "set" -> {
                w.nothrow = false;
                if (w.containsFrozen(recvType)) {
                    w.reportOnce("Cannot call mut method set on frozen value", call);
                }
                w.recordTargetMutation(recvOrig, receiverExpr, "mut method set", call, true);
                int valIdx = -1;
                for (int i = 0; i < args.size(); i++) {
                    if ("value".equals(ScopeAnalysis.callParamName(args.get(i)))) {
                        valIdx = i;
                        break;
                    }
                }
                if (valIdx < 0
                        && args.size() >= 2
                        && ScopeAnalysis.callParamName(args.get(1)) == null) {
                    valIdx = 1;
                }
                if (valIdx >= 0 && valIdx < argExprs.length) {
                    Reachability.Origin valOrig = argOrigins[valIdx];
                    Type valType = w.effectiveType(argExprs[valIdx]);
                    if (!ec.javaResolver.isDeeplyImmutable(valType)) {
                        if (valOrig.owned() && valOrig.uniqueOwner != null) {
                            w.demoteUniqueOwner(valOrig.uniqueOwner, Reachability.Origin.IMMUTABLE);
                        }
                        w.markStoredIntoTarget(
                                recvOrig, valOrig, valType, "element", argExprs[valIdx]);
                    }
                }
                yield Reachability.Origin.IMMUTABLE;
            }
            case "equals" -> {
                w.applyEffects(ec.javaResolver.singleEqualityProfile(arr.element()));
                w.recordTargetRead(recvOrig, recvType);
                if (argExprs.length == 1) {
                    w.recordTargetRead(argOrigins[0], w.effectiveType(argExprs[0]));
                }
                yield Reachability.Origin.IMMUTABLE;
            }
            case "hashCode" -> {
                w.applyEffects(ec.javaResolver.singleEqualityProfile(arr.element()));
                w.recordTargetRead(recvOrig, recvType);
                yield Reachability.Origin.IMMUTABLE;
            }
            case "toString" -> {
                w.applyEffects(ec.javaResolver.toStringProfile(arr.element()));
                w.recordTargetRead(recvOrig, recvType);
                yield Reachability.Origin.IMMUTABLE;
            }
            default -> Reachability.Origin.IMMUTABLE;
        };
    }

    private Reachability.Origin evalRangeMethodCall(
            ParserRuleContext call,
            Type elemType,
            String methodName,
            BabbleParser.ExpressionContext[] argExprs,
            Type resType) {
        if (resType == Type.ERROR) {
            return Reachability.Origin.IMMUTABLE;
        }
        Class<?> ownerClass = rangeRuntimeClass(elemType);
        Class<?>[] paramTypes =
                switch (methodName) {
                    case "contains" -> new Class<?>[] {Object.class};
                    case "step" -> new Class<?>[] {elemType == Type.LONG ? long.class : int.class};
                    case "equals" -> new Class<?>[] {Object.class};
                    default -> new Class<?>[0];
                };
        java.lang.reflect.Method m = null;
        try {
            m = ownerClass.getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException ignored) {
            // Fall through to name-based lookup below.
        }
        if (m == null) {
            m = JavaEffectResolver.lookupPublicMethod(ownerClass, methodName);
        }
        EffectChecker.JavaCallProfile prof =
                ec.javaResolver.resolveJavaMethodProfile(ownerClass, methodName, m);
        EffectSet eff = prof.effects();
        if ("step".equals(methodName)
                && argExprs.length == 1
                && EffectBodyWalker.isPositiveIntegerConstant(argExprs[0])
                && eff.isPure()
                && eff.isHermetic()
                && eff.isDeterministic()) {
            eff =
                    EffectSet.ofInferred(
                            java.util.EnumSet.of(
                                    org.babblelang.effects.Effect.PURE,
                                    org.babblelang.effects.Effect.HERMETIC,
                                    org.babblelang.effects.Effect.DETERMINISTIC,
                                    org.babblelang.effects.Effect.NOTHROW),
                            false,
                            false,
                            false,
                            true);
        } else if (elemType == Type.STRING
                && ("contains".equals(methodName)
                        || "isEmpty".equals(methodName)
                        || "equals".equals(methodName)
                        || "hashCode".equals(methodName)
                        || "toString".equals(methodName))
                && eff.isPure()
                && eff.isHermetic()
                && eff.isDeterministic()) {
            // String.compareTo / equals / hashCode / toString are pure nothrow in
            // java/lang/curated.ba.
            EffectSet cmpEff = ec.javaResolver.comparisonProfile(Type.STRING, Type.STRING);
            if (cmpEff.isNothrow()) {
                eff =
                        EffectSet.ofInferred(
                                java.util.EnumSet.of(
                                        org.babblelang.effects.Effect.PURE,
                                        org.babblelang.effects.Effect.HERMETIC,
                                        org.babblelang.effects.Effect.DETERMINISTIC,
                                        org.babblelang.effects.Effect.NOTHROW),
                                false,
                                false,
                                false,
                                true);
            }
        }
        w.applyEffects(eff);
        return ec.javaResolver.isDeeplyImmutable(resType)
                ? Reachability.Origin.IMMUTABLE
                : Reachability.Origin.ofUnique(call, null, false);
    }

    private static Class<?> rangeRuntimeClass(Type elemType) {
        if (elemType == Type.BYTE || elemType == Type.SHORT || elemType == Type.INT) {
            return org.babblelang.runtime.IntRange.class;
        }
        if (elemType == Type.LONG) {
            return org.babblelang.runtime.LongRange.class;
        }
        if (elemType == Type.CHAR) {
            return org.babblelang.runtime.CharRange.class;
        }
        if (elemType == Type.FLOAT || elemType == Type.DOUBLE) {
            return org.babblelang.runtime.DoubleRange.class;
        }
        return org.babblelang.runtime.Range.class;
    }

    private Type resolveCalleeType(BabbleParser.ExpressionContext calleeExpr) {
        return ec.checker.rawTypeOf(calleeExpr);
    }

    private boolean isImportCall(BabbleParser.ExpressionContext calleeExpr) {
        if (calleeExpr.getParent() instanceof BabbleParser.CallContext cc
                && BlockScopes.staticImport(cc) != null) {
            // Even static import("...ba") executes module initialization at runtime unless
            // top-level!
            return true;
        }
        if (calleeExpr instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            String name = sel.ID().getText();
            if (("import".equals(name) || "importe".equals(name))
                    && ec.checker.resolveNameForEffects(name, ec.checker.occurrenceScopeOf(sel))
                            == null) {
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
        if (genericType instanceof java.lang.reflect.ParameterizedType pt
                && pt.getRawType() instanceof Class<?> raw) {
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
                } else if (bound instanceof java.lang.reflect.ParameterizedType pt
                        && pt.getRawType() instanceof Class<?> raw) {
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

    private Reachability.Origin applyJavaCall(
            ParserRuleContext call,
            BabbleParser.ExpressionContext calleeExpr,
            EffectChecker.JavaCallProfile profile,
            Class<?> owner,
            String methodName,
            Class<?>[] javaParamTypes,
            java.lang.reflect.Type[] genericParamTypes,
            boolean varArgs,
            BabbleParser.ExpressionContext receiverExpr,
            Reachability.Origin recvOrig,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins,
            Type resType) {
        if (resType == Type.ERROR) {
            return Reachability.Origin.IMMUTABLE;
        }
        w.applyEffects(profile.effects());
        if (ec.checker.hasCallCheck(call) && !profile.explicitNothrow()) {
            w.nothrow = false;
        }

        // Receiver mut / borrow / frozen / read check:
        Type recvType = receiverExpr != null ? w.effectiveType(receiverExpr) : null;
        if (receiverExpr != null && profile.readsReceiver()) {
            w.recordTargetRead(recvOrig, recvType);
        }
        boolean effectiveMutRecv =
                profile.mutReceiver() && receiverExpr != null && recvType != Type.STRING;
        if (receiverExpr != null && w.containsFrozen(recvType)) {
            if (effectiveMutRecv) {
                w.reportOnce("Cannot call mut method " + methodName + " on frozen value", call);
            } else if (!profile.borrowReceiver() && !profile.effects().isPure()) {
                w.reportOnce(
                        "Cannot call non-borrow method " + methodName + " on frozen value", call);
            }
        }
        if (effectiveMutRecv) {
            w.recordTargetMutation(
                    recvOrig,
                    receiverExpr,
                    "mut Java method " + calleeExpr.getText(),
                    call,
                    profile.shallow());
        }
        boolean recvCanEscape =
                !profile.borrowReceiver()
                        && !profile.effects().isPure()
                        && !(profile.purity().mutatesNothing()
                                && ec.javaResolver.isDeeplyImmutable(resType));
        if (receiverExpr != null && recvCanEscape) {
            if (recvOrig.owned() && recvOrig.uniqueOwner != null) {
                w.demoteUniqueOwner(recvOrig.uniqueOwner, Reachability.Origin.IMMUTABLE);
            }
            w.markStoredOrigin(recvOrig, "Java method call", call);
        }

        // Parameter mut / borrow / unique / frozen / read / SAM callback checks:
        boolean[] argIsMut = new boolean[argExprs.length];
        int fixed = varArgs && javaParamTypes != null ? javaParamTypes.length - 1 : -1;
        for (int i = 0; i < argExprs.length; i++) {
            Type argType = w.effectiveType(argExprs[i]);
            int pIdx = varArgs && fixed >= 0 ? Math.min(i, fixed) : i;
            boolean mutP =
                    pIdx < profile.mutParams().length
                            && profile.mutParams()[pIdx]
                            && argType != Type.STRING;
            boolean borrowP = pIdx < profile.borrowParams().length && profile.borrowParams()[pIdx];
            boolean uniqueP = pIdx < profile.uniqueParams().length && profile.uniqueParams()[pIdx];
            boolean readP = pIdx < profile.readParams().length ? profile.readParams()[pIdx] : true;
            if (readP) {
                w.recordTargetRead(argOrigins[i], argType);
            }
            argIsMut[i] = mutP;
            if (w.containsFrozen(argType)) {
                if (mutP) {
                    w.reportOnce("Cannot pass frozen value to mut parameter", argExprs[i]);
                } else if (uniqueP) {
                    w.reportOnce("Argument for unique parameter cannot be frozen", argExprs[i]);
                } else if (!borrowP) {
                    w.reportOnce(
                            "Cannot pass frozen value to non-borrow Java parameter", argExprs[i]);
                }
            }
            if (uniqueP && !argOrigins[i].moved) {
                if (w.containsFrozen(argType)) {
                    // Already reported above.
                } else if (!argOrigins[i].unique
                        && argOrigins[i] != Reachability.Origin.IMMUTABLE
                        && !ec.javaResolver.isDeeplyImmutable(argType)
                        && argType != Type.ERROR) {
                    w.reportOnce("Argument for unique parameter must be unique", argExprs[i]);
                } else if (!borrowP
                        && argOrigins[i].unique
                        && argOrigins[i].uniqueOwner instanceof ParserRuleContext decl) {
                    if (w.paramIndex.containsKey(decl) && w.borrowParams[w.paramIndex.get(decl)]) {
                        w.reportOnce(
                                "Cannot move borrowed parameter " + argExprs[i].getText(),
                                argExprs[i]);
                    } else {
                        w.env.put(decl, Reachability.Origin.MOVED);
                    }
                }
            }
            if (mutP) {
                w.recordTargetMutation(
                        argOrigins[i], argExprs[i], "mut Java parameter", argExprs[i], false);
            }
            if (!borrowP) {
                // Storing a non-unique, non-immutable arg into a unique/localFresh receiver keeps
                // container localFresh while dropping unique (BIP016-D9)!
                if (recvOrig.owned()
                        && effectiveMutRecv
                        && !w.isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                    w.absorbElementIntoLocalContainer(recvOrig.uniqueOwner, argOrigins[i]);
                }
                if (!uniqueP && argOrigins[i].owned() && argOrigins[i].uniqueOwner != null) {
                    w.demoteUniqueOwner(argOrigins[i].uniqueOwner, Reachability.Origin.IMMUTABLE);
                }
                w.markStoredOrigin(argOrigins[i], "Java call argument", argExprs[i]);
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
                javaParamClass =
                        (varArgs
                                        && i >= fixed
                                        && javaParamTypes[fixed] != null
                                        && javaParamTypes[fixed].isArray())
                                ? javaParamTypes[fixed].getComponentType()
                                : javaParamTypes[pIdx];
            }
            if (javaParamClass != null && JavaTypes.isFunctionalInterface(javaParamClass)) {
                propagateSamCallbackEffects(argType, javaParamClass);
            }
        }

        // Static call-site mut exclusivity:
        checkCallExclusivity(
                receiverExpr, recvOrig, effectiveMutRecv, argExprs, argOrigins, argIsMut, call);

        // Result origin (BIP019 §4.1: w.deterministic callees return fresh or
        // argument/receiver-derived results):
        if (ec.javaResolver.isDeeplyImmutable(resType)) {
            return Reachability.Origin.IMMUTABLE;
        }
        if (profile.fresh() || profile.effects().uniqueResult()) {
            return Reachability.Origin.ofUnique(call, null, false);
        }
        if (receiverExpr != null && profile.view()) {
            return recvOrig.deriveMember("$" + methodName, false);
        }
        boolean deterministicCall =
                profile.effects().isHermetic() && profile.effects().isDeterministic();
        if ("<init>".equals(methodName)
                || JavaEffectResolver.isCollectionFactoryMethod(owner, methodName)) {
            if (deterministicCall) {
                Reachability.Origin fresh = Reachability.Origin.ofUnique(call, null, false);
                boolean staysUnique = true;
                for (int i = 0; i < argOrigins.length; i++) {
                    Type argType = w.effectiveType(argExprs[i]);
                    if (!ec.javaResolver.isDeeplyImmutable(argType)
                            && !w.isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                        staysUnique = false;
                        fresh = fresh.absorbElement(argOrigins[i]);
                    }
                }
                return staysUnique ? Reachability.Origin.ofUnique(call, null, false) : fresh;
            }
            return Reachability.Origin.ofExternal(call, false);
        }
        if (deterministicCall) {
            Reachability.Origin out = Reachability.Origin.IMMUTABLE;
            if (receiverExpr != null && recvOrig != Reachability.Origin.IMMUTABLE) {
                Reachability.Origin flatRecv = recvOrig.flattenElements();
                out = flatRecv.stripDirectIdentity(false);
            }
            for (int i = 0; i < argOrigins.length; i++) {
                if (argOrigins[i] != Reachability.Origin.IMMUTABLE) {
                    Reachability.Origin flatArg = argOrigins[i].flattenElements();
                    Reachability.Origin stripped = flatArg.stripDirectIdentity(false);
                    out = out == Reachability.Origin.IMMUTABLE ? stripped : out.join(stripped);
                }
            }
            if (out == Reachability.Origin.IMMUTABLE) {
                return Reachability.Origin.ofUnique(call, null, false);
            }
            if (out.owned()) {
                out = out.withoutUnique();
            }
            return out;
        }
        Reachability.Origin out = Reachability.Origin.ofExternal(call, false);
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
                BabbleParser.FunctionLiteralContext lit = ec.checker.literalOf(fn);
                if (lit != null && ec.summaries.containsKey(lit)) {
                    w.applyCallableSummaryEffects(ec.summaries.get(lit));
                } else {
                    w.applyEffects(fn.effects());
                }
            } else if (m instanceof Type.Instance inst) {
                Method sam = JavaTypes.functionalMethod(samClass);
                if (sam != null) {
                    TypeNode.Member impl = ec.checker.findMemberOnNode(inst.node(), sam.getName());
                    if (impl != null
                            && impl.function != null
                            && ec.summaries.containsKey(impl.function)) {
                        w.applyCallableSummaryEffects(ec.summaries.get(impl.function));
                    } else if (impl != null
                            && ec.checker.memberTypeForEffects(inst.node(), impl)
                                    instanceof Type.Function tf) {
                        w.applyEffects(tf.effects());
                    } else {
                        w.hermetic = false;
                        w.deterministic = false;
                        w.nothrow = false;
                    }
                }
            } else if (m != Type.ERROR) {
                w.hermetic = false;
                w.deterministic = false;
                w.nothrow = false;
            }
        }
    }

    private Reachability.Origin applyBabbleCallableType(
            ParserRuleContext call,
            BabbleParser.ExpressionContext calleeExpr,
            Type calleeType,
            BabbleParser.ExpressionContext receiverExpr,
            Reachability.Origin recvOrig,
            List<BabbleParser.CallParameterContext> args,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins,
            Type resType) {
        List<Type> members = Type.membersOf(calleeType);
        Reachability.Origin combinedOut = Reachability.Origin.IMMUTABLE;
        for (Type member : members) {
            if (!(member instanceof Type.Function fn)) {
                continue;
            }
            Reachability.Origin memberOut =
                    applyBabbleCallableMember(
                            call,
                            calleeExpr,
                            fn,
                            null,
                            receiverExpr,
                            recvOrig,
                            args,
                            argExprs,
                            argOrigins,
                            resType);
            combinedOut =
                    combinedOut == Reachability.Origin.IMMUTABLE
                            ? memberOut
                            : combinedOut.join(memberOut);
        }
        return combinedOut;
    }

    private Reachability.Origin applyBabbleCallableMember(
            ParserRuleContext call,
            BabbleParser.ExpressionContext calleeExpr,
            Type.Function fn,
            LoweringTargets.SpreadAdapt[] explicitAdapts,
            BabbleParser.ExpressionContext receiverExpr,
            Reachability.Origin recvOrig,
            List<BabbleParser.CallParameterContext> args,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins,
            Type resType) {
        EffectChecker.CallableSummary summary =
                resolveBabbleCallSummary(fn, calleeExpr, receiverExpr, args, argExprs, argOrigins);
        w.applyCallableSummaryEffects(summary);
        boolean isMethodCall =
                receiverExpr != null
                        || calleeExpr instanceof BabbleParser.SuperExpressionContext
                        || (calleeExpr instanceof BabbleParser.SelectorContext s
                                && s.expression() instanceof BabbleParser.SuperExpressionContext);
        Reachability.Origin effectiveRecvOrig =
                (calleeExpr instanceof BabbleParser.SelectorContext s
                                && s.expression() instanceof BabbleParser.SuperExpressionContext)
                        ? Reachability.Origin.ofThis(Reachability.THIS_KEY, false)
                        : recvOrig;
        boolean effectiveBorrowRecv =
                !isMethodCall
                        || summary.effects().borrowThis()
                        || (summary.purity().mutatesNothing()
                                && ec.javaResolver.isDeeplyImmutable(resType));
        if (isMethodCall && summary.readsThis()) {
            Type rType = receiverExpr != null ? w.effectiveType(receiverExpr) : Type.ANY;
            w.recordTargetRead(effectiveRecvOrig, rType);
        }
        boolean[] calleeRead = summary.readParams();
        for (int i = 0; i < args.size(); i++) {
            int pIdx = ec.checker.parameterIndexForArg(fn.parameters(), args, args.get(i));
            if (pIdx >= 0 && pIdx < calleeRead.length && calleeRead[pIdx]) {
                w.recordTargetRead(argOrigins[i], w.effectiveType(argExprs[i]));
            }
        }
        boolean[] mutableCopiedSpread = new boolean[args.size()];
        Reachability.Origin[] postSpreadOrigins =
                applyBabbleArgEffects(
                        call,
                        calleeExpr,
                        fn.parameters(),
                        summary.mutParams(),
                        summary.borrowParams(),
                        summary.uniqueParams(),
                        isMethodCall ? receiverExpr : null,
                        effectiveRecvOrig,
                        isMethodCall && summary.effects().mutThis(),
                        summary.deepMutThis(),
                        effectiveBorrowRecv,
                        summary.effects().isHermetic(),
                        args,
                        argExprs,
                        argOrigins,
                        explicitAdapts,
                        mutableCopiedSpread);
        if (!fn.forwardedEffectParams().isEmpty()) {
            applyForwardedCallbackEffects(
                    call,
                    fn,
                    isMethodCall ? receiverExpr : null,
                    effectiveRecvOrig,
                    args,
                    argExprs,
                    argOrigins);
        }
        if (summary.effects().uniqueResult()) {
            return Reachability.Origin.ofUnique(
                    call, null, ec.javaResolver.isDeeplyImmutable(resType));
        }
        return deriveCallReturnOrigin(
                call,
                resType,
                summary,
                isMethodCall,
                effectiveRecvOrig,
                argOrigins,
                postSpreadOrigins,
                mutableCopiedSpread,
                fn.parameters(),
                args);
    }

    private boolean isForwardedParamExpr(BabbleParser.ExpressionContext expr) {
        BabbleParser.ExpressionContext unwrapped = EffectBodyWalker.unwrapParens(expr);
        if (!(unwrapped instanceof BabbleParser.SelectorContext s) || s.expression() != null) {
            return false;
        }
        Object resolved =
                ec.checker.resolveNameForEffects(s.ID().getText(), ec.checker.occurrenceScopeOf(s));
        if (!(resolved instanceof ParserRuleContext decl)) {
            return false;
        }
        Integer idx = w.paramIndex.get(decl);
        if (idx != null && w.forwardedParams.contains(idx)) {
            return true;
        }
        if (decl instanceof BabbleParser.FnParamContext param) {
            if (w.owner instanceof BabbleParser.FunctionLiteralContext ownerFn
                    && EffectModifiers.capturesCallback(ownerFn, param)) {
                return true;
            }
            for (BabbleParser.FunctionLiteralContext encFn : w.frameChain) {
                List<BabbleParser.FnParamContext> encParams = encFn.fnParameters().fnParam();
                int encIdx = encParams.indexOf(decl);
                if (encIdx >= 0
                                && EffectModifiers.resolveForwardedParamIndices(encFn)
                                        .contains(encIdx)
                        || EffectModifiers.capturesCallback(encFn, param)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The effects of calling a function value that still forwards captured callbacks (inside the
     * function that captures them, before a call instantiates them, BIP017-D5) : those of each
     * callback, unless the current function forwards it in turn.
     */
    private EffectSet withCapturedCallbackEffects(
            Type.Function fn, BabbleParser.ExpressionContext calleeExpr) {
        EffectSet effects = fn.effects();
        for (String name : fn.capturedCallings()) {
            Object resolved =
                    ec.checker.resolveNameForEffects(
                            name, ec.checker.occurrenceScopeOf(calleeExpr));
            if (resolved instanceof BabbleParser.FnParamContext param) {
                if (forwardsParameter(param)) {
                    w.stmtHasSideEffect = true;
                    continue;
                }
                if (ec.checker.typeOfDeclarationForEffects(param) instanceof Type.Function cb) {
                    effects = effects.combineForwarded(cb.effects());
                    continue;
                }
            }
            effects = effects.combineForwarded(EffectSet.IO_DECLARED);
        }
        return effects;
    }

    private boolean forwardsParameter(BabbleParser.FnParamContext param) {
        Integer idx = w.paramIndex.get(param);
        if (idx != null && w.forwardedParams.contains(idx)) {
            return true;
        }
        if (w.owner instanceof BabbleParser.FunctionLiteralContext ownerFn
                && EffectModifiers.capturesCallback(ownerFn, param)) {
            return true;
        }
        for (BabbleParser.FunctionLiteralContext encFn : w.frameChain) {
            int encIdx = encFn.fnParameters().fnParam().indexOf(param);
            if (encIdx >= 0 && EffectModifiers.resolveForwardedParamIndices(encFn).contains(encIdx)
                    || EffectModifiers.capturesCallback(encFn, param)) {
                return true;
            }
        }
        return false;
    }

    void applyForwardedCallbackEffects(
            ParserRuleContext call,
            Type.Function hof,
            BabbleParser.ExpressionContext receiverExpr,
            Reachability.Origin recvOrig,
            List<BabbleParser.CallParameterContext> args,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins) {
        for (int i = 0; i < args.size(); i++) {
            int pIdx = ec.checker.parameterIndexForArg(hof.parameters(), args, args.get(i));
            if (!hof.forwardedEffectParams().contains(pIdx)) {
                continue;
            }
            BabbleParser.ExpressionContext argExpr = argExprs[i];
            if (isForwardedParamExpr(argExpr)) {
                w.stmtHasSideEffect = true;
                continue;
            }
            Type argType = w.effectiveType(argExpr);
            for (Type m : Type.membersOf(argType)) {
                if (!(m instanceof Type.Function cbFn)) {
                    continue;
                }
                EffectSet cbEffects;
                BabbleParser.FunctionLiteralContext cbLit = ec.checker.literalOf(cbFn);
                if (cbLit != null && ec.summaries.containsKey(cbLit)) {
                    EffectChecker.CallableSummary cbSum = ec.summaries.get(cbLit);
                    w.applyCallableSummaryEffects(cbSum);
                    cbEffects = cbSum.effects();
                } else {
                    w.applyEffects(cbFn.effects());
                    cbEffects = cbFn.effects();
                }
                if (!cbEffects.isHermetic() || !cbEffects.isDeterministic()) {
                    w.stmtHasSideEffect = true;
                }
                if (cbEffects.mutThis()) {
                    BabbleParser.ExpressionContext unwrapped =
                            EffectBodyWalker.unwrapParens(argExpr);
                    if (unwrapped instanceof BabbleParser.SelectorContext s
                            && s.expression() != null) {
                        Reachability.Origin boundRecvOrig = w.boundMethodReceivers.get(s);
                        if (boundRecvOrig != null) {
                            if (w.containsFrozen(w.effectiveType(s.expression()))) {
                                w.reportOnce("Cannot call mut method on frozen value", call);
                            } else {
                                w.recordTargetMutation(
                                        boundRecvOrig,
                                        s.expression(),
                                        "forwarded mut callback " + s.getText(),
                                        call,
                                        false);
                            }
                            if (receiverExpr != null
                                    && Reachability.staticallySameObject(boundRecvOrig, recvOrig)) {
                                w.reportOnce(
                                        "Exclusivity error : mut target "
                                                + s.expression().getText()
                                                + " aliases argument "
                                                + receiverExpr.getText(),
                                        call);
                            }
                            for (int j = 0; j < argExprs.length; j++) {
                                if (j != i
                                        && Reachability.staticallySameObject(
                                                boundRecvOrig, argOrigins[j])) {
                                    w.reportOnce(
                                            "Exclusivity error : mut target "
                                                    + s.expression().getText()
                                                    + " aliases argument "
                                                    + argExprs[j].getText(),
                                            argExprs[j]);
                                }
                            }
                        } else {
                            w.hermetic = false;
                            w.deterministic = false;
                            w.stmtHasSideEffect = true;
                        }
                    } else {
                        w.hermetic = false;
                        w.deterministic = false;
                        w.stmtHasSideEffect = true;
                    }
                }
            }
        }
    }

    private EffectChecker.CallableSummary resolveBabbleCallSummary(
            Type.Function fn,
            BabbleParser.ExpressionContext calleeExpr,
            BabbleParser.ExpressionContext receiverExpr,
            List<BabbleParser.CallParameterContext> args,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins) {
        // Forwarded callback parameter inside a `fn calling f` body: pure-modulo-forwarding
        // (BIP017-D1).
        if (isForwardedParamExpr(calleeExpr)) {
            w.stmtHasSideEffect = true;
            int n = fn.parameters().size();
            boolean[] mp = new boolean[n];
            boolean[] bp = new boolean[n];
            boolean[] up = new boolean[n];
            for (int i = 0; i < n; i++) {
                mp[i] = fn.parameters().get(i).mut();
                bp[i] = fn.parameters().get(i).borrow();
                up[i] = fn.parameters().get(i).unique();
            }
            EffectSet pureModForward =
                    EffectSet.ofInferred(
                            EnumSet.of(
                                    Effect.PURE,
                                    Effect.HERMETIC,
                                    Effect.DETERMINISTIC,
                                    Effect.NOTHROW),
                            false,
                            true,
                            fn.effects().uniqueResult(),
                            true);
            return new EffectChecker.CallableSummary(pureModForward, mp, bp, up);
        }

        EffectChecker.CallableSummary unforwarded =
                EffectModifiers.unforwardedEnclosingCallingSummary(ec, w, fn, calleeExpr);
        if (unforwarded != null) {
            return unforwarded;
        }

        // Single-pass symbolic callback parameter invocation (BIP027-I4): record C_called &
        // Flow[i][m],
        // keeping residual effects clean of the callback's pessimistic default type.
        int cbParamIdx = w.resolveParamCallbackIndex(calleeExpr);
        if (cbParamIdx >= 0) {
            w.stmtHasSideEffect = true;
            w.calledCallbacks.set(cbParamIdx);
            for (int i = 0; i < args.size(); i++) {
                int slotIdx = ec.checker.parameterIndexForArg(fn.parameters(), args, args.get(i));
                if (slotIdx >= 0) {
                    w.recordCallbackArgFlow(cbParamIdx, slotIdx, argOrigins[i]);
                }
            }
            int n = fn.parameters().size();
            boolean[] mp = new boolean[n];
            boolean[] bp = EffectChecker.allTrue(n);
            boolean[] up = new boolean[n];
            for (int i = 0; i < n; i++) {
                up[i] = fn.parameters().get(i).unique();
                if (up[i]) {
                    bp[i] = false;
                }
            }
            EffectSet pureResidual =
                    EffectSet.ofInferred(
                            EnumSet.of(
                                    Effect.PURE,
                                    Effect.HERMETIC,
                                    Effect.DETERMINISTIC,
                                    Effect.NOTHROW),
                            false,
                            true,
                            fn.effects().uniqueResult(),
                            true);
            return new EffectChecker.CallableSummary(pureResidual, mp, bp, up);
        }

        // Built-ins (assert, same, classof, int, double):
        if (ec.checker.isBuiltinFunction(fn)) {
            int n = fn.parameters().size();
            boolean[] mp = new boolean[n];
            boolean[] bp = new boolean[n];
            boolean[] up = new boolean[n];
            for (int i = 0; i < n; i++) {
                mp[i] = fn.parameters().get(i).mut();
                bp[i] = fn.parameters().get(i).borrow();
                up[i] = fn.parameters().get(i).unique();
            }
            return new EffectChecker.CallableSummary(fn.effects(), mp, bp, up);
        }

        // Statically resolved concrete Babble function literal (intra-file):
        BabbleParser.FunctionLiteralContext targetLit =
                resolveConcreteBabbleTarget(fn, calleeExpr, receiverExpr);
        if (targetLit != null && EffectModifiers.capturesCallbacks(targetLit)) {
            // Its summary takes the captured callbacks as pure : the type has their effects
            // (BIP017-D5).
            targetLit = null;
        } else if (targetLit != null) {
            EffectChecker.ParsedPrefix targetPrefix =
                    ec.modifiers.parseFunctionPrefix(targetLit, false);
            if (!targetPrefix.axiom()
                    && !targetPrefix.explicitVolatile()
                    && !targetPrefix.io()
                    && fn.forwardedEffectParams().isEmpty()) {
                SymbolicEffectSummary sym = ec.symbolicSummaries.get(targetLit);
                if (sym != null && sym.isParametric()) {
                    return instantiateSymbolicCall(
                            sym, fn.parameters(), args, argExprs, argOrigins);
                }
            }
            EffectChecker.CallableSummary base = ec.summaries.get(targetLit);
            if (base != null) {
                if (targetLit != w.owner
                        && (targetPrefix.io() || targetPrefix.explicitVolatile())) {
                    EffectChecker.CallableSummary vis = ec.visibleSummaries.get(targetLit);
                    return vis != null ? vis : ec.buildVisibleSummary(targetLit, base);
                }
                return ec.visibleSummaries.getOrDefault(targetLit, base);
            }
        } else if (!(receiverExpr != null
                && w.effectiveType(receiverExpr) instanceof Type.Instance inst
                && inst.node().isInterface())) {
            TypedModuleSummary.CallableEffectTemplate template =
                    ec.checker.remoteCallableTemplate(fn);
            if (template != null) {
                if (template.summary().isParametric()) {
                    return instantiateSymbolicCall(
                            template.summary(), fn.parameters(), args, argExprs, argOrigins);
                }
                return template.base();
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
            rp[i] = !ec.javaResolver.isDeeplyImmutable(p.type());
        }
        EffectSet effects =
                fn.capturedCallings().isEmpty()
                        ? fn.effects()
                        : withCapturedCallbackEffects(fn, calleeExpr);
        return new EffectChecker.CallableSummary(
                effects,
                mp,
                bp,
                up,
                Set.of(),
                Set.of(),
                effects.mutThis(),
                EffectChecker.EMPTY_SUMMARY_BITS,
                false,
                false,
                false,
                EffectChecker.EMPTY_SUMMARY_BITS,
                rp,
                true);
    }

    private EffectChecker.CallableSummary instantiateSymbolicCall(
            SymbolicEffectSummary sym,
            List<Type.Parameter> calleeParams,
            List<BabbleParser.CallParameterContext> args,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins) {
        int n = calleeParams.size();
        Type.Function[] boundCallbacks = new Type.Function[n];
        EffectChecker.CallableSummary[] boundSummaries = new EffectChecker.CallableSummary[n];
        Reachability.Origin[] originByParam = new Reachability.Origin[n];
        for (int i = 0; i < args.size(); i++) {
            int pIdx = ec.checker.parameterIndexForArg(calleeParams, args, args.get(i));
            if (pIdx >= 0 && pIdx < n) {
                originByParam[pIdx] = argOrigins[i];
            }
        }
        EffectSet purePlaceholder =
                EffectSet.ofInferred(
                        EnumSet.of(
                                Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                        false,
                        true,
                        false,
                        true);
        for (int i = 0; i < args.size(); i++) {
            int pIdx = ec.checker.parameterIndexForArg(calleeParams, args, args.get(i));
            if (pIdx < 0 || pIdx >= n || !sym.calledCallbacks.get(pIdx)) {
                continue;
            }
            int ownerCbIdx = w.resolveParamCallbackIndex(argExprs[i]);
            if (ownerCbIdx >= 0) {
                // Transitive HOF forwarding: compose callee's symbolic callback flow into our own
                // symbolic summary.
                w.calledCallbacks.set(ownerCbIdx);
                if (pIdx < sym.paramFlow.length && sym.paramFlow[pIdx] != null) {
                    long[] masks = sym.paramFlow[pIdx];
                    for (int m = 0; m < masks.length; m++) {
                        long mask = masks[m];
                        for (int j = 0; j < n && j < 64; j++) {
                            if ((mask & (1L << j)) != 0 && originByParam[j] != null) {
                                w.recordCallbackArgFlow(ownerCbIdx, m, originByParam[j]);
                            }
                        }
                    }
                }
                Type argType = ec.checker.rawTypeOf(argExprs[i]);
                if (argType instanceof Type.Function argFn) {
                    boundCallbacks[pIdx] =
                            argFn.withEffects(
                                    purePlaceholder,
                                    EffectChecker.allBorrowParams(argFn.parameters()));
                }
                continue;
            }
            Type argType = ec.checker.rawTypeOf(argExprs[i]);
            if (argType instanceof Type.Function argFn) {
                BabbleParser.FunctionLiteralContext argLit = ec.checker.literalOf(argFn);
                if (argLit != null) {
                    EffectChecker.CallableSummary argSum = ec.summaries.get(argLit);
                    if (argSum != null) {
                        argFn = enrichFunctionType(argFn, argSum);
                        boundSummaries[pIdx] = argSum;
                    }
                }
                boundCallbacks[pIdx] = argFn;
            }
        }
        return sym.instantiate(boundCallbacks, boundSummaries);
    }

    private BabbleParser.FunctionLiteralContext resolveConcreteBabbleTarget(
            Type.Function fn,
            BabbleParser.ExpressionContext calleeExpr,
            BabbleParser.ExpressionContext receiverExpr) {
        if (receiverExpr != null) {
            Type recvType = w.effectiveType(receiverExpr);
            if (recvType instanceof Type.Instance inst && inst.node().isInterface()) {
                // Abstract or overridable interface dispatch cannot be specialized statically
                // (§4.5)!
                return null;
            }
        }
        return ec.checker.literalOf(fn);
    }

    Reachability.Origin[] applyBabbleArgEffects(
            ParserRuleContext call,
            BabbleParser.ExpressionContext calleeExpr,
            List<Type.Parameter> calleeParams,
            boolean[] calleeMut,
            boolean[] calleeBorrow,
            boolean[] calleeUnique,
            BabbleParser.ExpressionContext receiverExpr,
            Reachability.Origin recvOrig,
            boolean mutRecv,
            boolean deepMutRecv,
            boolean borrowRecv,
            boolean calleeHermetic,
            List<BabbleParser.CallParameterContext> args,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins) {
        return applyBabbleArgEffects(
                call,
                calleeExpr,
                calleeParams,
                calleeMut,
                calleeBorrow,
                calleeUnique,
                receiverExpr,
                recvOrig,
                mutRecv,
                deepMutRecv,
                borrowRecv,
                calleeHermetic,
                args,
                argExprs,
                argOrigins,
                null,
                null);
    }

    private Reachability.Origin[] applyBabbleArgEffects(
            ParserRuleContext call,
            BabbleParser.ExpressionContext calleeExpr,
            List<Type.Parameter> calleeParams,
            boolean[] calleeMut,
            boolean[] calleeBorrow,
            boolean[] calleeUnique,
            BabbleParser.ExpressionContext receiverExpr,
            Reachability.Origin recvOrig,
            boolean mutRecv,
            boolean deepMutRecv,
            boolean borrowRecv,
            boolean calleeHermetic,
            List<BabbleParser.CallParameterContext> args,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins,
            LoweringTargets.SpreadAdapt[] explicitAdapts,
            boolean[] outMutableCopiedSpread) {
        if (receiverExpr != null && w.containsFrozen(w.effectiveType(receiverExpr))) {
            if (mutRecv) {
                w.reportOnce("Cannot call mut method on frozen value", call);
            } else if (!borrowRecv) {
                w.reportOnce("Cannot call shared method on frozen value", call);
            }
        }
        boolean effectiveMutRecv =
                mutRecv
                        && (receiverExpr != null || !recvOrig.isEmpty())
                        && (receiverExpr == null || w.effectiveType(receiverExpr) != Type.STRING);
        if (effectiveMutRecv) {
            w.recordTargetMutation(
                    recvOrig,
                    receiverExpr != null ? receiverExpr : call,
                    "mut method " + calleeExpr.getText(),
                    call,
                    !deepMutRecv);
        }
        if (!borrowRecv) {
            if (recvOrig.owned() && recvOrig.uniqueOwner != null) {
                w.demoteUniqueOwner(recvOrig.uniqueOwner, Reachability.Origin.IMMUTABLE);
            }
            w.markStoredOrigin(recvOrig, "method call " + calleeExpr.getText(), call);
        }

        boolean localFreshRecvOnly =
                calleeHermetic
                        && effectiveMutRecv
                        && recvOrig.owned()
                        && !recvOrig.external
                        && recvOrig.params.isEmpty()
                        && !recvOrig.aliasesThis
                        && !EffectChecker.anyTrue(calleeMut);
        boolean[] argIsExclusive = new boolean[args.size()];
        Reachability.Origin[] postSpreadOrigins = new Reachability.Origin[args.size()];
        for (int i = 0; i < args.size(); i++) {
            int pIdx = ec.checker.parameterIndexForArg(calleeParams, args, args.get(i));
            Type argType = w.effectiveType(argExprs[i]);
            Reachability.Origin effectiveArgOrig = argOrigins[i];
            boolean copiedSpread = false;
            boolean mutCopiedSpread = false;
            if (ScopeAnalysis.isSpread(args.get(i))) {
                w.recordTargetRead(argOrigins[i], argType);
                LoweringTargets.SpreadAdapt adapt =
                        explicitAdapts != null
                                ? (i < explicitAdapts.length ? explicitAdapts[i] : null)
                                : ec.checker.spreadAdapt(call, i);
                if (adapt != null
                        && (adapt.kind() == LoweringTargets.SpreadKind.TO_LIST_COPY
                                || adapt.kind() == LoweringTargets.SpreadKind.TO_ARRAY)) {
                    copiedSpread = true;
                    Type elemType = ec.checker.extractSequenceElement(argType);
                    if ((elemType != null && ec.javaResolver.isDeeplyImmutable(elemType))
                            || w.isUnattachedUniqueOrImmutable(argOrigins[i], argType)) {
                        effectiveArgOrig = Reachability.Origin.ofUnique(argExprs[i], null, false);
                    } else {
                        mutCopiedSpread = true;
                        effectiveArgOrig =
                                Reachability.Origin.ofUnique(argExprs[i], null, false)
                                        .absorbElement(argOrigins[i]);
                    }
                }
            }
            if (outMutableCopiedSpread != null && i < outMutableCopiedSpread.length) {
                outMutableCopiedSpread[i] = mutCopiedSpread;
            }
            postSpreadOrigins[i] = effectiveArgOrig;
            boolean mutP =
                    pIdx >= 0
                            && pIdx < calleeMut.length
                            && calleeMut[pIdx]
                            && argType != Type.STRING;
            boolean borrowP = pIdx >= 0 && pIdx < calleeBorrow.length && calleeBorrow[pIdx];
            boolean uniqueP = pIdx >= 0 && pIdx < calleeUnique.length && calleeUnique[pIdx];
            argIsExclusive[i] = mutP;
            if (uniqueP && !effectiveArgOrig.moved) {
                if (w.constructingSelf && InitEffectAnalyzer.isDirectThisExpr(argExprs[i])) {
                    w.reportOnce("Cannot move or consume this inside init", argExprs[i]);
                } else {
                    String pName =
                            pIdx >= 0 && pIdx < calleeParams.size()
                                    ? calleeParams.get(pIdx).name()
                                    : ("#" + i);
                    if (!copiedSpread
                            && w.containsFrozen(argType)
                            && effectiveArgOrig == argOrigins[i]) {
                        w.reportOnce(
                                "Argument for unique parameter " + pName + " cannot be frozen",
                                argExprs[i]);
                    } else if (!effectiveArgOrig.unique
                            && effectiveArgOrig != Reachability.Origin.IMMUTABLE
                            && !ec.javaResolver.isDeeplyImmutable(argType)
                            && argType != Type.ERROR) {
                        w.reportOnce(
                                "Argument for unique parameter " + pName + " must be unique",
                                argExprs[i]);
                    } else if (!borrowP
                            && effectiveArgOrig.unique
                            && effectiveArgOrig.uniqueOwner instanceof ParserRuleContext decl) {
                        if (w.paramIndex.containsKey(decl)
                                && w.borrowParams[w.paramIndex.get(decl)]) {
                            w.reportOnce(
                                    "Cannot move borrowed parameter " + argExprs[i].getText(),
                                    argExprs[i]);
                        } else {
                            w.env.put(decl, Reachability.Origin.MOVED);
                        }
                    }
                }
            }
            if (mutP) {
                w.recordTargetMutation(
                        effectiveArgOrig,
                        argExprs[i],
                        "mut parameter " + calleeParams.get(pIdx).name(),
                        argExprs[i],
                        false);
            }
            if (!borrowP) {
                if (recvOrig.owned()
                        && effectiveMutRecv
                        && !w.isUnattachedUniqueOrImmutable(effectiveArgOrig, argType)) {
                    w.absorbElementIntoLocalContainer(recvOrig.uniqueOwner, effectiveArgOrig);
                }
                if (!uniqueP && effectiveArgOrig.owned() && effectiveArgOrig.uniqueOwner != null) {
                    w.demoteUniqueOwner(
                            effectiveArgOrig.uniqueOwner, Reachability.Origin.IMMUTABLE);
                }
                if (mutCopiedSpread && argOrigins[i].owned() && argOrigins[i].uniqueOwner != null) {
                    w.demoteUniqueOwner(argOrigins[i].uniqueOwner, Reachability.Origin.IMMUTABLE);
                }
                if (!localFreshRecvOnly) {
                    w.markStoredOrigin(
                            effectiveArgOrig, "call to " + calleeExpr.getText(), argExprs[i]);
                }
            }
        }

        checkCallExclusivity(
                receiverExpr,
                recvOrig,
                effectiveMutRecv,
                argExprs,
                argOrigins,
                argIsExclusive,
                call);
        return postSpreadOrigins;
    }

    private Reachability.Origin deriveCallReturnOrigin(
            ParserRuleContext call,
            Type resType,
            EffectChecker.CallableSummary summary,
            boolean isMethodCall,
            Reachability.Origin recvOrig,
            Reachability.Origin[] rawArgOrigins,
            Reachability.Origin[] argOrigins,
            boolean[] mutableCopiedSpread,
            List<Type.Parameter> calleeParams,
            List<BabbleParser.CallParameterContext> args) {
        if (ec.javaResolver.isDeeplyImmutable(resType)) {
            return Reachability.Origin.IMMUTABLE;
        }
        boolean resultDiscarded = call != null && call == w.discardedExpr;
        boolean known = summary.returnKnown();
        boolean deterministicCallee =
                summary.effects().isHermetic() && summary.effects().isDeterministic();
        boolean includeExternal = known ? summary.returnExternal() : !deterministicCallee;
        Reachability.Origin out =
                includeExternal
                        ? Reachability.Origin.ofExternal(null, false)
                        : Reachability.Origin.IMMUTABLE;
        boolean includeRecv =
                isMethodCall && (!known || summary.returnThis() || !summary.effects().borrowThis());
        if (includeRecv && recvOrig != Reachability.Origin.IMMUTABLE) {
            if (!resultDiscarded && recvOrig.owned() && recvOrig.uniqueOwner != null) {
                w.demoteUniqueOwner(recvOrig.uniqueOwner, Reachability.Origin.IMMUTABLE);
            }
            Reachability.Origin flatRecv = recvOrig.flattenElements();
            out = out == Reachability.Origin.IMMUTABLE ? flatRecv : out.join(flatRecv);
        }
        boolean[] calleeBorrow = summary.borrowParams();
        for (int i = 0; i < args.size(); i++) {
            int pIdx = ec.checker.parameterIndexForArg(calleeParams, args, args.get(i));
            boolean borrowP = pIdx >= 0 && pIdx < calleeBorrow.length && calleeBorrow[pIdx];
            boolean returnedP =
                    !known || (pIdx >= 0 && summary.returnParams().get(pIdx)) || !borrowP;
            if (returnedP && argOrigins[i] != Reachability.Origin.IMMUTABLE) {
                if (!resultDiscarded
                        && argOrigins[i].owned()
                        && argOrigins[i].uniqueOwner != null) {
                    w.demoteUniqueOwner(argOrigins[i].uniqueOwner, Reachability.Origin.IMMUTABLE);
                }
                if (!resultDiscarded
                        && mutableCopiedSpread != null
                        && i < mutableCopiedSpread.length
                        && mutableCopiedSpread[i]
                        && rawArgOrigins[i].owned()
                        && rawArgOrigins[i].uniqueOwner != null) {
                    w.demoteUniqueOwner(
                            rawArgOrigins[i].uniqueOwner, Reachability.Origin.IMMUTABLE);
                }
                Reachability.Origin flatArg = argOrigins[i].flattenElements();
                out = out == Reachability.Origin.IMMUTABLE ? flatArg : out.join(flatArg);
            }
        }
        if (out == Reachability.Origin.IMMUTABLE) {
            return Reachability.Origin.ofUnique(call, null, false);
        }
        if (known
                && summary.purity() == Purity.PURE
                && !(isMethodCall && summary.effects().mutThis())
                && summary.frameWriteOwners().isEmpty()
                && out.localFresh()
                && out.uniqueOwner == null
                && out.params.isEmpty()
                && !out.aliasesThis
                && !out.external) {
            return new Reachability.Origin(
                    new BitSet(0),
                    false,
                    false,
                    false,
                    out.elements(),
                    false,
                    null,
                    Set.of(),
                    null);
        }
        if (out.owned()) {
            out = out.withoutUnique();
        }
        return out;
    }

    private void checkCallExclusivity(
            BabbleParser.ExpressionContext receiverExpr,
            Reachability.Origin recvOrig,
            boolean mutRecv,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins,
            boolean[] argIsMut,
            ParserRuleContext call) {
        int total = (receiverExpr != null ? 1 : 0) + argExprs.length;
        if (total < 2) {
            return;
        }
        Reachability.Origin[] origins = new Reachability.Origin[total];
        boolean[] muts = new boolean[total];
        ParserRuleContext[] sites = new ParserRuleContext[total];
        String[] labels = new String[total];
        int offset = 0;
        if (receiverExpr != null) {
            origins[0] =
                    w.effectiveType(receiverExpr) == Type.STRING
                            ? Reachability.Origin.IMMUTABLE
                            : recvOrig;
            muts[0] = mutRecv;
            sites[0] = receiverExpr;
            labels[0] = receiverExpr.getText();
            offset = 1;
        }
        for (int i = 0; i < argExprs.length; i++) {
            origins[offset + i] =
                    w.effectiveType(argExprs[i]) == Type.STRING
                            ? Reachability.Origin.IMMUTABLE
                            : argOrigins[i];
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
                if (Reachability.staticallySameObject(origins[i], origins[j])) {
                    w.reportOnce(
                            "Exclusivity error : mut target "
                                    + labels[i]
                                    + " aliases argument "
                                    + labels[j],
                            sites[Math.max(i, j)]);
                    return;
                }
            }
        }
    }

    private static Type.Function enrichFunctionType(
            Type.Function argFn, EffectChecker.CallableSummary argSum) {
        List<Type.Parameter> updatedParams = new ArrayList<>(argFn.parameters().size());
        for (int k = 0; k < argFn.parameters().size(); k++) {
            Type.Parameter p = argFn.parameters().get(k);
            boolean m = k < argSum.mutParams().length && argSum.mutParams()[k];
            boolean b = k < argSum.borrowParams().length && argSum.borrowParams()[k];
            updatedParams.add(p.withModifiers(m, b));
        }
        return argFn.withEffects(argSum.effects(), updatedParams);
    }
}
