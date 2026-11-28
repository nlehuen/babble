package org.babblelang.analysis;

import java.util.EnumSet;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.effects.Effect;
import org.babblelang.parser.BabbleParser;

/**
 * Core collection, array, and range call-site effect analysis extracted from {@link
 * CallEffectAnalyzer}.
 */
final class CollectionEffectAnalyzer {
    private CollectionEffectAnalyzer() {}

    static Reachability.Origin evalCoreCollectionCall(
            CallEffectAnalyzer calls,
            EffectChecker ec,
            EffectBodyWalker w,
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
                        if (java.lang.Iterable.class.isAssignableFrom(ji.type())) {
                            EffectChecker.JavaCallProfile iterProf =
                                    ec.javaResolver.resolveJavaMethodProfile(
                                            ji.type(),
                                            "iterator",
                                            JavaEffectResolver.lookupPublicMethod(
                                                    ji.type(), "iterator"));
                            w.applyEffects(iterProf.effects());
                            if (!iterProf.effects().isHermetic()) {
                                w.terminating = false;
                            }
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
                    } else if (fromType instanceof Type.Instance inst) {
                        applyBabbleIteratorFromEffects(ec, w, inst);
                        staysUnique = false;
                        w.terminating = false;
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

        // list, set, sortedset, map, sortedmap: hermetic deterministic allocation (BIP007-D6)
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
                Type rawFrom = w.effectiveType(argExprs[i]);
                w.recordTargetRead(argOrigins[i], rawFrom);
                Type fromType = rawFrom instanceof Type.Frozen f ? f.of() : rawFrom;
                if (fromType instanceof Type.JavaInstance ji) {
                    if (java.lang.Iterable.class.isAssignableFrom(ji.type())) {
                        EffectChecker.JavaCallProfile iterProf =
                                ec.javaResolver.resolveJavaMethodProfile(
                                        ji.type(),
                                        "iterator",
                                        JavaEffectResolver.lookupPublicMethod(
                                                ji.type(), "iterator"));
                        w.applyEffects(iterProf.effects());
                        if (!iterProf.effects().isNothrow()) {
                            w.nothrow = false;
                        }
                        if (!iterProf.effects().isHermetic() || !iterProf.effects().terminating()) {
                            w.terminating = false;
                        }
                    } else if (java.util.Map.class.isAssignableFrom(ji.type())) {
                        EffectChecker.JavaCallProfile entryProf =
                                ec.javaResolver.resolveJavaMethodProfile(
                                        ji.type(),
                                        "entrySet",
                                        JavaEffectResolver.lookupPublicMethod(
                                                ji.type(), "entrySet"));
                        w.applyEffects(entryProf.effects());
                        if (!entryProf.effects().isNothrow()) {
                            w.nothrow = false;
                        }
                        if (!entryProf.effects().isHermetic()
                                || !entryProf.effects().terminating()) {
                            w.terminating = false;
                        }
                    }
                } else if (fromType instanceof Type.Instance inst) {
                    applyBabbleIteratorFromEffects(ec, w, inst);
                    w.nothrow = false;
                    w.terminating = false;
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
                calls.propagateSamCallbackEffects(cmpType, java.util.Comparator.class);
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

    private static void applyBabbleIteratorFromEffects(
            EffectChecker ec, EffectBodyWalker w, Type.Instance inst) {
        TypeNode.Member iterMem = ec.checker.findMemberOnNode(inst.node(), "iterator");
        if (iterMem != null) {
            Type mt = ec.checker.memberTypeForEffects(inst.node(), iterMem);
            if (mt instanceof Type.Function tf) {
                BabbleParser.FunctionLiteralContext lit =
                        iterMem.function != null ? iterMem.function : ec.checker.literalOf(tf);
                EffectChecker.CallableSummary sum = lit != null ? ec.summaries.get(lit) : null;
                if (sum != null) {
                    w.applyCallableSummaryEffects(sum);
                } else {
                    w.applyEffects(tf.effects());
                }
            }
        }
    }

    static boolean isArrayProtocolMethod(String name) {
        return "size".equals(name)
                || "get".equals(name)
                || "set".equals(name)
                || "equals".equals(name)
                || "hashCode".equals(name)
                || "toString".equals(name);
    }

    static boolean isNonReassignedBareBinding(
            EffectChecker ec, BabbleParser.ExpressionContext expr) {
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

    static Reachability.Origin evalArrayOfCall(
            EffectChecker ec,
            EffectBodyWalker w,
            ParserRuleContext call,
            List<BabbleParser.CallParameterContext> paramsList) {
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

    static Reachability.Origin evalArrayMethodCall(
            EffectChecker ec,
            EffectBodyWalker w,
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
                yield recvOrig.deriveIndexedProjection(
                        InitEffectAnalyzer.literalIndexSegment(argExprs), false);
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

    static Reachability.Origin evalRangeMethodCall(
            EffectChecker ec,
            EffectBodyWalker w,
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
                            EnumSet.of(
                                    Effect.PURE,
                                    Effect.HERMETIC,
                                    Effect.DETERMINISTIC,
                                    Effect.NOTHROW),
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
            EffectSet cmpEff = ec.javaResolver.comparisonProfile(Type.STRING, Type.STRING);
            if (cmpEff.isNothrow()) {
                eff =
                        EffectSet.ofInferred(
                                EnumSet.of(
                                        Effect.PURE,
                                        Effect.HERMETIC,
                                        Effect.DETERMINISTIC,
                                        Effect.NOTHROW),
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
}
