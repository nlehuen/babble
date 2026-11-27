package org.babblelang.analysis;

import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

/**
 * The origin of a Java call's result (BIP019 §4.1) : deterministic callees return fresh or
 * argument/receiver-derived results, a {@code view} is part of its receiver, and a new object
 * ({@code fresh}, {@code unique}, a constructor or a collection factory) absorbs the arguments it
 * may hold, so that a projection of it is never taken for a unique value (BIP032 `O3`).
 */
final class JavaCallResultOrigins {
    private JavaCallResultOrigins() {}

    static Reachability.Origin of(
            EffectChecker ec,
            EffectBodyWalker w,
            ParserRuleContext call,
            EffectChecker.JavaCallProfile profile,
            Class<?> owner,
            String methodName,
            BabbleParser.ExpressionContext receiverExpr,
            Reachability.Origin recvOrig,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins,
            Type resType) {
        // Result origin (BIP019 §4.1: w.deterministic callees return fresh or
        // argument/receiver-derived results):
        if (ec.javaResolver.isDeeplyImmutable(resType)) {
            return Reachability.Origin.IMMUTABLE;
        }
        if (profile.fresh() || profile.effects().uniqueResult()) {
            return freshResultOrigin(ec, w, call, profile, argExprs, argOrigins);
        }
        if (receiverExpr != null && profile.view()) {
            // A view is part of its receiver, and may also be a non-immutable argument returned as
            // is (Optional.orElse(other)) : then it is not unique.
            Reachability.Origin view = recvOrig.deriveProjection(false);
            boolean mayReturnArgument = false;
            for (int i = 0; i < argOrigins.length; i++) {
                if (argOrigins[i] != Reachability.Origin.IMMUTABLE
                        && !ec.javaResolver.isDeeplyImmutable(w.effectiveType(argExprs[i]))) {
                    view = view.join(argOrigins[i]);
                    mayReturnArgument = true;
                }
            }
            return mayReturnArgument && view.owned() ? view.withoutUnique() : view;
        }
        if (receiverExpr != null
                && argExprs.length >= 1
                && ("get".equals(methodName) || "getOrDefault".equals(methodName))
                && (java.util.List.class.isAssignableFrom(owner)
                        || java.util.Map.class.isAssignableFrom(owner))) {
            Reachability.Origin proj =
                    recvOrig.deriveIndexedProjection(
                            InitEffectAnalyzer.literalIndexSegment(argExprs), false);
            if ("getOrDefault".equals(methodName)
                    && argOrigins.length >= 2
                    && argOrigins[1] != Reachability.Origin.IMMUTABLE) {
                proj = proj.join(argOrigins[1].deriveProjection(false));
            }
            return proj.owned() ? proj.withoutUnique() : proj;
        }
        boolean deterministicCall =
                profile.effects().isHermetic() && profile.effects().isDeterministic();
        if ("<init>".equals(methodName)
                || JavaEffectResolver.isCollectionFactoryMethod(owner, methodName)) {
            if (deterministicCall) {
                return freshResultOrigin(ec, w, call, profile, argExprs, argOrigins);
            }
            return Reachability.Origin.ofExternal(call, false);
        }
        if (deterministicCall) {
            Reachability.Origin out = Reachability.Origin.IMMUTABLE;
            if (receiverExpr != null && recvOrig != Reachability.Origin.IMMUTABLE) {
                out = recvOrig.flattenElements().deriveProjection(false);
            }
            for (int i = 0; i < argOrigins.length; i++) {
                if (argOrigins[i] != Reachability.Origin.IMMUTABLE) {
                    Reachability.Origin proj =
                            argOrigins[i].flattenElements().deriveProjection(false);
                    out = out == Reachability.Origin.IMMUTABLE ? proj : out.join(proj);
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
            out = out.join(recvOrig.deriveProjection(false));
        }
        for (int i = 0; i < argOrigins.length; i++) {
            boolean borrowP = i < profile.borrowParams().length && profile.borrowParams()[i];
            if (!borrowP) {
                out = out.join(argOrigins[i].deriveProjection(false));
            }
        }
        return out;
    }

    /**
     * The origin of a new object a call returns : unique, its elements absorbing every argument it
     * may hold (an {@code Optional.of(a)}, a {@code List.of(a)}), so that a projection of it is not
     * taken for a unique value. Deeply immutable and unattached unique arguments are not absorbed,
     * nor borrowed ones whose elements are all immutable : the new object can keep at most the
     * elements of a borrowed argument ({@code Arrays.copyOf(ints, n)} holds nothing of {@code
     * ints}).
     */
    private static Reachability.Origin freshResultOrigin(
            EffectChecker ec,
            EffectBodyWalker w,
            ParserRuleContext call,
            EffectChecker.JavaCallProfile profile,
            BabbleParser.ExpressionContext[] argExprs,
            Reachability.Origin[] argOrigins) {
        Reachability.Origin fresh = Reachability.Origin.ofUnique(call, null, false);
        for (int i = 0; i < argOrigins.length; i++) {
            Type argType = w.effectiveType(argExprs[i]);
            boolean borrowed = i < profile.borrowParams().length && profile.borrowParams()[i];
            if (!ec.javaResolver.isDeeplyImmutable(argType)
                    && !w.isUnattachedUniqueOrImmutable(argOrigins[i], argType)
                    && !(borrowed && holdsOnlyImmutable(ec, argType))) {
                fresh = fresh.absorbElement(argOrigins[i]);
            }
        }
        return fresh;
    }

    /** Whether every element of an array or collection type is primitive or deeply immutable. */
    private static boolean holdsOnlyImmutable(EffectChecker ec, Type type) {
        List<Type> elements =
                switch (Type.withoutNull(TypeAlgebra.unwrapFrozen(type))) {
                    case Type.Array a -> List.of(a.element());
                    case Type.JavaInstance ji -> ji.typeArguments();
                    case Type.CoreCollection cc -> cc.typeArguments();
                    default -> null;
                };
        return elements != null
                && !elements.isEmpty()
                && elements.stream()
                        .allMatch(e ->
                                Type.isPrimitiveScalar(e) || ec.javaResolver.isDeeplyImmutable(e));
    }
}
