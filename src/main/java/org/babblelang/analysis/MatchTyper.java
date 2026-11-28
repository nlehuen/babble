package org.babblelang.analysis;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.parser.StringLiterals;
import org.babblelang.runtime.ClassShape;

/**
 * Type-checking, narrowing, and lowering fact collection for {@code match} expressions ({@code
 * BIP038}). Collaborator of {@link TypeChecker}.
 */
final class MatchTyper {
    record RecordDestructureInfo(
            boolean isJavaRecord,
            TypeNode babbleNode,
            Class<?> javaRecordClass,
            List<String> fieldNames,
            int[] babbleMemberIndices,
            List<Type> fieldTypes,
            List<Method> javaAccessors,
            Type targetType) {}

    record SequenceDestructureInfo(
            boolean isArray,
            Class<?> arrayClass,
            Type elemType,
            int fixedCount,
            boolean hasRest,
            Type targetType) {}

    private final TypeChecker checker;
    private final Map<ParserRuleContext, Type> patternTargetTypes = new IdentityHashMap<>();
    private final Map<ParserRuleContext, String> patternBuiltins = new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.PatternConst> patternConsts =
            new IdentityHashMap<>();
    private final Map<BabbleParser.ValuePatternContext, LoweringMetadata.RangePatternSource>
            rangePatterns = new IdentityHashMap<>();
    private final Map<BabbleParser.MembershipPatternContext, LoweringTargets.MembershipTarget>
            membershipTargets = new IdentityHashMap<>();
    private final Map<BabbleParser.RelationalPatternContext, PrimitiveKind> relationalKinds =
            new IdentityHashMap<>();
    private final Map<BabbleParser.MatchExpressionContext, Boolean> matchExhaustive =
            new IdentityHashMap<>();
    private final Map<ParserRuleContext, RecordDestructureInfo> recordDestructures =
            new IdentityHashMap<>();
    private final Map<ParserRuleContext, SequenceDestructureInfo> sequenceDestructures =
            new IdentityHashMap<>();

    MatchTyper(TypeChecker checker) {
        this.checker = checker;
    }

    void reset() {
        patternTargetTypes.clear();
        patternBuiltins.clear();
        patternConsts.clear();
        rangePatterns.clear();
        membershipTargets.clear();
        relationalKinds.clear();
        matchExhaustive.clear();
        recordDestructures.clear();
        sequenceDestructures.clear();
    }

    void clearSubtree(ParserRuleContext ctx) {
        patternTargetTypes.remove(ctx);
        patternBuiltins.remove(ctx);
        patternConsts.remove(ctx);
        recordDestructures.remove(ctx);
        sequenceDestructures.remove(ctx);
        if (ctx instanceof BabbleParser.ValuePatternContext vp) {
            rangePatterns.remove(vp);
        }
        if (ctx instanceof BabbleParser.MembershipPatternContext mp) {
            membershipTargets.remove(mp);
        }
        if (ctx instanceof BabbleParser.RelationalPatternContext rp) {
            relationalKinds.remove(rp);
        }
        if (ctx instanceof BabbleParser.MatchExpressionContext m) {
            matchExhaustive.remove(m);
        }
    }

    Map<ParserRuleContext, Type> patternTargetTypes() {
        return patternTargetTypes;
    }

    Map<ParserRuleContext, String> patternBuiltins() {
        return patternBuiltins;
    }

    Map<ParserRuleContext, LoweringTargets.PatternConst> patternConsts() {
        return patternConsts;
    }

    Map<BabbleParser.ValuePatternContext, LoweringMetadata.RangePatternSource> rangePatterns() {
        return rangePatterns;
    }

    Map<BabbleParser.MembershipPatternContext, LoweringTargets.MembershipTarget>
            membershipTargets() {
        return membershipTargets;
    }

    Map<BabbleParser.RelationalPatternContext, PrimitiveKind> relationalKinds() {
        return relationalKinds;
    }

    Map<BabbleParser.MatchExpressionContext, Boolean> matchExhaustive() {
        return matchExhaustive;
    }

    Map<ParserRuleContext, RecordDestructureInfo> recordDestructures() {
        return recordDestructures;
    }

    Map<ParserRuleContext, SequenceDestructureInfo> sequenceDestructures() {
        return sequenceDestructures;
    }

    private record PatternTyping(Type matchedType, Type subtractedType) {}

    Type typeMatch(BabbleParser.MatchExpressionContext ctx, Type expected, TypeChecker.Role role) {
        Type scrutineeType = checker.synth(ctx.scrutinee);
        ParserRuleContext scrutineeDecl = checker.narrowing().narrowableDeclaration(ctx.scrutinee);
        TypeEnv baseEnv = checker.env();
        TypeEnv fallthroughEnv = baseEnv;
        Type remaining = scrutineeType;

        List<Type> branchTypes = new ArrayList<>();
        List<ParserRuleContext> branchNodes = new ArrayList<>();
        List<TypeEnv> branchEnvs = new ArrayList<>();
        List<BabbleParser.CaseClauseContext> clauses = ctx.caseClause();
        boolean[] armHadError = new boolean[clauses.size()];
        boolean anyPatternError = false;

        for (int ci = 0; ci < clauses.size(); ci++) {
            BabbleParser.CaseClauseContext clause = clauses.get(ci);
            checker.setEnv(fallthroughEnv);
            PatternTyping pt = typePattern(clause.pattern(), scrutineeType, remaining);
            if (pt.matchedType() == Type.ERROR) {
                armHadError[ci] = true;
                anyPatternError = true;
            }

            TypeEnv armEnv = fallthroughEnv;
            if (scrutineeDecl != null
                    && pt.matchedType() != Type.ERROR
                    && pt.matchedType() != Type.NOTHING
                    && !pt.matchedType().equals(remaining)) {
                armEnv = armEnv.apply(Map.of(scrutineeDecl, pt.matchedType()));
            }
            checker.setEnv(armEnv);
            if (clause.guard != null) {
                checker.synth(clause.guard);
                TypeEnv.Refinement guardRef = checker.narrowing().extractRefinement(clause.guard);
                armEnv = checker.env().apply(guardRef.whenTrue());
                checker.setEnv(armEnv);
            }
            Type armType = checker.type(clause.armBlock, expected, role);
            branchTypes.add(armType);
            branchNodes.add(clause.armBlock);
            branchEnvs.add(checker.env());

            if (clause.guard == null && pt.subtractedType() != null) {
                Type nextRemaining = pt.subtractedType();
                if (scrutineeDecl != null
                        && nextRemaining != Type.NOTHING
                        && nextRemaining != Type.ERROR
                        && !nextRemaining.equals(remaining)) {
                    fallthroughEnv = fallthroughEnv.apply(Map.of(scrutineeDecl, nextRemaining));
                }
                remaining = nextRemaining;
            }
        }

        boolean hasElse = ctx.elseClause() != null;
        if (hasElse) {
            BabbleParser.ElseClauseContext ec = ctx.elseClause();
            if (ec.name != null) {
                Type elseBindingType =
                        (remaining == Type.NOTHING || remaining == Type.ERROR)
                                ? scrutineeType
                                : remaining;
                checker.declarationTypes().put(ec, elseBindingType);
            }
            checker.setEnv(fallthroughEnv);
            Type elseType = checker.type(ec.elseBlock, expected, role);
            branchTypes.add(elseType);
            branchNodes.add(ec.elseBlock);
            branchEnvs.add(checker.env());
        }

        // Exhaustiveness, reachability, and redundant-else checks (BIP038-I4)
        boolean exhaustive = hasElse;
        if (scrutineeType != Type.ERROR && !TypeChecker.isUnresolved(scrutineeType)) {
            List<MatchExhaustiveness.Arm> exArms = new ArrayList<>(clauses.size());
            for (int ci = 0; ci < clauses.size(); ci++) {
                BabbleParser.CaseClauseContext clause = clauses.get(ci);
                MatchExhaustiveness.Pat pat =
                        armHadError[ci]
                                ? MatchExhaustiveness.Pat.Opaque.INSTANCE
                                : toExhaustivenessPat(clause.pattern());
                exArms.add(new MatchExhaustiveness.Arm(pat, clause.guard != null));
            }
            MatchExhaustiveness.Result res =
                    MatchExhaustiveness.check(
                            scrutineeType,
                            exArms,
                            hasElse,
                            checker.algebra(),
                            this::recordFieldTypesForExhaustiveness);
            for (int idx : res.unreachableArms()) {
                if (!armHadError[idx]) {
                    checker.warn(
                            "Unreachable case : already matched by earlier case", clauses.get(idx));
                }
            }
            if (!anyPatternError) {
                if (!res.exhaustive() && !hasElse) {
                    String msg =
                            res.missingWitness() != null
                                    ? "Non-exhaustive match : missing " + res.missingWitness()
                                    : "Non-exhaustive match : add an else branch";
                    checker.warn(msg, ctx);
                } else if (res.redundantElse()) {
                    checker.analysisWarn(
                            "Redundant else : match is already exhaustive", ctx.elseClause());
                }
            }
            exhaustive = res.exhaustive() || hasElse;
        }
        matchExhaustive.put(ctx, exhaustive);

        TypeEnv joinedEnv = null;
        for (int i = 0; i < branchTypes.size(); i++) {
            if (branchTypes.get(i) != Type.NOTHING) {
                joinedEnv =
                        joinedEnv == null
                                ? branchEnvs.get(i)
                                : TypeEnv.joinBranches(
                                        baseEnv, joinedEnv, branchEnvs.get(i), checker.algebra());
            }
        }
        checker.setEnv(joinedEnv != null ? joinedEnv : baseEnv);

        boolean used = role.isUsed();
        boolean allNothing = true;
        for (Type bt : branchTypes) {
            if (bt != Type.NOTHING) {
                allNothing = false;
                break;
            }
        }
        if (allNothing) {
            return Type.NOTHING;
        }
        if (expected != null && expected != Type.ERROR && used) {
            for (int i = 0; i < branchTypes.size(); i++) {
                if (branchTypes.get(i) != Type.NOTHING) {
                    checker.subsumes(branchNodes.get(i), branchTypes.get(i), expected);
                }
            }
            return expected;
        }
        Type joined = null;
        for (Type bt : branchTypes) {
            if (bt == Type.NOTHING) {
                continue;
            }
            if (joined == null) {
                joined = bt;
            } else {
                joined = checker.algebra().join(joined, bt);
                if (joined == null) {
                    if (!used) {
                        return Type.NULL;
                    }
                    return Type.ERROR;
                }
            }
        }
        if (joined == null) {
            return Type.NULL;
        }
        for (int i = 0; i < branchTypes.size(); i++) {
            if (branchTypes.get(i) != Type.NOTHING) {
                checker.coerce(branchNodes.get(i), branchTypes.get(i), joined);
            }
        }
        return joined;
    }

    private PatternTyping typePattern(
            BabbleParser.PatternContext pat, Type domain, Type remaining) {
        List<BabbleParser.AtomicPatternContext> atoms = pat.atomicPattern();
        if (atoms.size() == 1) {
            return typeAtomicPattern(atoms.get(0), domain, remaining);
        }
        List<Type> matched = new ArrayList<>(atoms.size());
        Type curRem = remaining;
        boolean hadError = false;
        for (BabbleParser.AtomicPatternContext atom : atoms) {
            PatternTyping pt = typeAtomicPattern(atom, domain, curRem);
            if (pt.matchedType() == Type.ERROR) {
                hadError = true;
            } else if (pt.matchedType() != Type.NOTHING) {
                matched.add(pt.matchedType());
            }
            if (pt.subtractedType() != null) {
                curRem = pt.subtractedType();
            }
        }
        if (hadError) {
            return new PatternTyping(Type.ERROR, curRem);
        }
        Type unionMatched = matched.isEmpty() ? remaining : Type.union(matched);
        return new PatternTyping(unionMatched, curRem);
    }

    private PatternTyping typeAtomicPattern(
            BabbleParser.AtomicPatternContext atom, Type domain, Type remaining) {
        return switch (atom) {
            case BabbleParser.ValuePatternContext vp -> typeValuePattern(vp, domain, remaining);
            case BabbleParser.RelationalPatternContext rp ->
                    typeRelationalPattern(rp, domain, remaining);
            case BabbleParser.MembershipPatternContext mp ->
                    typeMembershipPattern(mp, domain, remaining);
            case BabbleParser.TypeOrDestructurePatternContext tp ->
                    typeIsPattern(tp, domain, remaining);
            case BabbleParser.BindingPatternContext bp -> typeBindingPattern(bp, domain, remaining);
            default -> new PatternTyping(remaining, remaining);
        };
    }

    // ---------------------------------------------------------------- value & range patterns

    private PatternTyping typeValuePattern(
            BabbleParser.ValuePatternContext vp, Type domain, Type remaining) {
        if (BlockScopes.isWildcardExpr(vp.expr)) {
            return new PatternTyping(remaining, Type.NOTHING);
        }
        SteppedRange extracted = extractRangePattern(vp.expr);
        if (extracted != null) {
            return typeRangeOrRangeEqPattern(vp, extracted, domain, remaining);
        }
        Type valueExpected =
                expectedForValuePattern(remaining != Type.NOTHING ? remaining : domain);
        Type patType =
                valueExpected != null
                        ? checker.check(vp.expr, valueExpected)
                        : checker.synth(vp.expr);
        if (patType == Type.ERROR || TypeChecker.isUnresolved(domain) || domain == Type.ERROR) {
            return new PatternTyping(remaining, remaining);
        }
        if (!overlapsValue(domain, patType)) {
            checker.warn("Impossible pattern : " + domain + " cannot match " + vp.getText(), vp);
            return new PatternTyping(Type.ERROR, remaining);
        }
        LoweringTargets.PatternConst constant = resolvePatternConst(vp.expr, patType);
        if (constant != null) {
            patternConsts.put(vp, constant);
            if (constant.isNull()) {
                Type subtracted = Type.withoutNull(remaining);
                return new PatternTyping(Type.NULL, subtracted);
            }
        }
        Type narrowBase = remaining != Type.NOTHING ? remaining : domain;
        Type matched =
                patType == Type.NULL
                        ? Type.NULL
                        : (Type.isNumericScalar(patType)
                                ? narrowBase
                                : checker.algebra().narrowTarget(narrowBase, patType));
        return new PatternTyping(matched, remaining);
    }

    private record SteppedRange(
            BabbleParser.RangeExpressionContext range, BabbleParser.ExpressionContext step) {}

    private static SteppedRange extractRangePattern(BabbleParser.ExpressionContext expr) {
        BabbleParser.ExpressionContext cur = unwrapSingleParen(expr);
        if (cur instanceof BabbleParser.RangeExpressionContext r) {
            return new SteppedRange(r, null);
        }
        if (cur instanceof BabbleParser.CallContext call
                && call.expression() instanceof BabbleParser.SelectorContext sel
                && sel.expression() != null
                && sel.safe == null
                && "step".equals(ScopeAnalysis.selectorName(sel))
                && call.callParameters().callParameter().size() == 1
                && call.callParameters().callParameter(0).spread == null) {
            BabbleParser.ExpressionContext recv = unwrapSingleParen(sel.expression());
            if (recv instanceof BabbleParser.RangeExpressionContext r) {
                return new SteppedRange(r, call.callParameters().callParameter(0).expression());
            }
        }
        return null;
    }

    private static BabbleParser.ExpressionContext unwrapSingleParen(
            BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext b
                && b.block().expression().size() == 1) {
            expr = b.block().expression(0);
        }
        return expr;
    }

    private PatternTyping typeRangeOrRangeEqPattern(
            BabbleParser.ValuePatternContext vp,
            SteppedRange extracted,
            Type domain,
            Type remaining) {
        Type active = remaining != Type.NOTHING ? remaining : domain;
        if (active == Type.ERROR || active == Type.NOTHING) {
            checker.synth(vp.expr);
            return new PatternTyping(remaining, remaining);
        }
        Type nonNullRem = Type.withoutNull(active);
        boolean isAnyOrVar = nonNullRem == Type.ANY || nonNullRem instanceof Type.TypeVar;
        boolean hasRange = false;
        boolean hasNonRange = false;
        for (Type m : Type.membersOf(nonNullRem)) {
            if (Type.rangeElement(m) != null) {
                hasRange = true;
            } else if (m != Type.NULL) {
                hasNonRange = true;
            }
        }
        if (isAnyOrVar || (hasRange && hasNonRange)) {
            checker.warn(
                    "Ambiguous range pattern : "
                            + active
                            + ". Use case in lo..hi or case == (lo..hi)",
                    vp);
            checker.synth(vp.expr);
            return new PatternTyping(Type.ERROR, remaining);
        }
        if (hasRange) {
            // Pure range scrutinee: bare range pattern is range equality `v == (lo..hi)`.
            Type patType = checker.synth(vp.expr);
            if (!overlapsValue(domain, patType)) {
                checker.warn(
                        "Impossible pattern : " + domain + " cannot match " + vp.getText(), vp);
            }
            return new PatternTyping(nonNullRem, remaining);
        }
        // Ordered element scrutinee: zero-allocation range-membership pattern!
        BabbleParser.RangeExpressionContext r = extracted.range();
        Type expectedBound = expectedForValuePattern(nonNullRem);
        Type loType =
                expectedBound != null
                        ? checker.check(r.left, expectedBound)
                        : checker.synth(r.left);
        Type hiType =
                expectedBound != null
                        ? checker.check(r.right, expectedBound)
                        : checker.synth(r.right);
        Type stepType = extracted.step() != null ? checker.synth(extracted.step()) : null;

        if (loType == Type.ERROR || hiType == Type.ERROR || stepType == Type.ERROR) {
            return new PatternTyping(Type.ERROR, remaining);
        }
        if (Type.containsNull(loType)) {
            checker.warn("Range bound cannot be null : " + loType, r.left);
            return new PatternTyping(Type.ERROR, remaining);
        }
        if (Type.containsNull(hiType)) {
            checker.warn("Range bound cannot be null : " + hiType, r.right);
            return new PatternTyping(Type.ERROR, remaining);
        }
        if (nonNullRem == Type.NOTHING
                || !OperatorTypes.comparable(nonNullRem, loType)
                || !OperatorTypes.comparable(nonNullRem, hiType)) {
            checker.warn("Impossible pattern : " + active + " cannot match " + vp.getText(), vp);
            return new PatternTyping(Type.ERROR, remaining);
        }
        if (extracted.step() != null) {
            if (!OperatorTypes.isIntegral(stepType)) {
                checker.warn("Not an integer : " + extracted.step().getText(), extracted.step());
                return new PatternTyping(Type.ERROR, remaining);
            }
            if (!Type.isDiscreteRangeElement(nonNullRem)) {
                checker.warn("Cannot step non-discrete range pattern : " + nonNullRem, vp);
                return new PatternTyping(Type.ERROR, remaining);
            }
        }
        boolean inclusive = r.op.getType() == BabbleLexer.RANGE;
        PrimitiveKind elemKind = PrimitiveKind.NONE;
        boolean stringElem = false;
        if (Type.isNumericScalar(nonNullRem)
                && Type.isNumericScalar(loType)
                && Type.isNumericScalar(hiType)) {
            int maxRank =
                    Math.max(
                            Type.numericRank(nonNullRem),
                            Math.max(Type.numericRank(loType), Type.numericRank(hiType)));
            Type promoted = Type.numericOfRank(maxRank);
            checker.coerce(r.left, loType, promoted);
            checker.coerce(r.right, hiType, promoted);
            elemKind = PrimitiveKind.of(promoted);
        } else if (nonNullRem == Type.CHAR && loType == Type.CHAR && hiType == Type.CHAR) {
            elemKind = PrimitiveKind.CHAR;
        } else if (nonNullRem == Type.STRING) {
            stringElem = true;
        }
        rangePatterns.put(
                vp,
                new LoweringMetadata.RangePatternSource(
                        r.left, r.right, extracted.step(), inclusive, elemKind, stringElem));
        return new PatternTyping(nonNullRem, remaining);
    }

    // ---------------------------------------------------------------- relational patterns

    private PatternTyping typeRelationalPattern(
            BabbleParser.RelationalPatternContext rp, Type domain, Type remaining) {
        Type active = remaining != Type.NOTHING ? remaining : domain;
        int opType = rp.op.getType();
        Type expectedRhs = expectedForValuePattern(active);
        Type rhsType =
                expectedRhs != null ? checker.check(rp.rhs, expectedRhs) : checker.synth(rp.rhs);
        if (rhsType == Type.ERROR || TypeChecker.isUnresolved(domain) || domain == Type.ERROR) {
            return new PatternTyping(remaining, remaining);
        }
        if (opType == BabbleLexer.EQ || opType == BabbleLexer.NEQ) {
            if (!overlapsValue(domain, rhsType)) {
                checker.warn(
                        "Impossible pattern : " + domain + " cannot match " + rp.getText(), rp);
                return new PatternTyping(Type.ERROR, remaining);
            }
            relationalKinds.put(rp, PrimitiveKind.of(active));
            return new PatternTyping(active, remaining);
        }
        Type nonNullActive = Type.withoutNull(active);
        if (nonNullActive == Type.NOTHING
                || Type.containsNull(rhsType)
                || !OperatorTypes.comparable(nonNullActive, rhsType)) {
            checker.warn("Impossible pattern : " + active + " cannot match " + rp.getText(), rp);
            return new PatternTyping(Type.ERROR, remaining);
        }
        relationalKinds.put(rp, PrimitiveKind.of(active));
        return new PatternTyping(nonNullActive, remaining);
    }

    // ---------------------------------------------------------------- membership (`in`) patterns

    private PatternTyping typeMembershipPattern(
            BabbleParser.MembershipPatternContext mp, Type domain, Type remaining) {
        Type containerType = checker.synth(mp.container);
        if (containerType == Type.ERROR || TypeChecker.isUnresolved(containerType)) {
            return new PatternTyping(remaining, remaining);
        }
        Type unwrapped = TypeAlgebra.unwrapFrozen(containerType);
        if (Type.containsNull(unwrapped)) {
            checker.warn(
                    "Type error : expected range, collection, map, or array, got " + containerType,
                    mp.container);
            return new PatternTyping(Type.ERROR, remaining);
        }
        LoweringTargets.MembershipKind kind;
        Type elemType;
        PrimitiveKind elemKind = PrimitiveKind.NONE;
        Type rangeElem = Type.rangeElement(unwrapped);
        if (rangeElem != null) {
            kind = LoweringTargets.MembershipKind.RANGE;
            elemType = rangeElem;
            elemKind = PrimitiveKind.of(rangeElem);
        } else if (unwrapped instanceof Type.Array arr) {
            kind = LoweringTargets.MembershipKind.ARRAY;
            elemType = arr.element();
            elemKind = PrimitiveKind.of(arr.element());
        } else if (checker.algebra().projectToJava(unwrapped, Collection.class)
                instanceof Type.JavaInstance col) {
            kind = LoweringTargets.MembershipKind.COLLECTION;
            elemType =
                    col.typeArguments().isEmpty() ? Type.NULLABLE_ANY : col.typeArguments().get(0);
        } else if (checker.algebra().projectToJava(unwrapped, Map.class)
                instanceof Type.JavaInstance map) {
            kind = LoweringTargets.MembershipKind.MAP;
            elemType =
                    map.typeArguments().isEmpty() ? Type.NULLABLE_ANY : map.typeArguments().get(0);
        } else if (unwrapped == Type.ANY) {
            kind = LoweringTargets.MembershipKind.DYNAMIC;
            elemType = Type.NULLABLE_ANY;
        } else {
            checker.warn(
                    "Type error : expected range, collection, map, or array, got " + containerType,
                    mp.container);
            return new PatternTyping(Type.ERROR, remaining);
        }
        membershipTargets.put(mp, new LoweringTargets.MembershipTarget(kind, elemKind));
        if (!TypeChecker.isUnresolved(domain)
                && domain != Type.ERROR
                && !overlapsValue(domain, elemType)) {
            checker.warn("Impossible pattern : " + domain + " cannot match " + mp.getText(), mp);
            return new PatternTyping(Type.ERROR, remaining);
        }
        return new PatternTyping(remaining != Type.NOTHING ? remaining : domain, remaining);
    }

    // ---------------------------------------------------------------- type & binding patterns

    private PatternTyping typeIsPattern(
            BabbleParser.TypeOrDestructurePatternContext tp, Type domain, Type remaining) {
        ScopeNode scope = checker.scopeOf(tp);
        if (tp.patternArgs() != null
                && isRawArrayPatternType(tp.patternType().nullableType(), scope)) {
            return typeArrayDestructure(tp, null, tp.patternArgs(), domain, remaining, null);
        }
        Type target =
                resolvePatternType(
                        tp.patternType().nullableType(),
                        tp.patternType(),
                        scope,
                        tp.patternArgs() != null,
                        tp);
        if (target == Type.ERROR) {
            return new PatternTyping(Type.ERROR, remaining);
        }
        if (tp.patternArgs() != null) {
            return typeDestructurePattern(tp, target, tp.patternArgs(), domain, remaining, null);
        }
        if (!TypeChecker.isUnresolved(domain)
                && domain != Type.ERROR
                && !checker.algebra().overlapsErased(domain, target)) {
            checker.warn("Impossible pattern : " + domain + " cannot match is " + target, tp);
            return new PatternTyping(Type.ERROR, remaining);
        }
        Type narrowBase =
                (remaining != Type.NOTHING && checker.algebra().overlapsErased(remaining, target))
                        ? remaining
                        : domain;
        Type matched =
                !TypeChecker.isUnresolved(narrowBase) && narrowBase != Type.ERROR
                        ? checker.algebra().narrowTarget(narrowBase, target)
                        : target;
        Type subtracted =
                !TypeChecker.isUnresolved(remaining) && remaining != Type.ERROR
                        ? checker.algebra().subtractType(remaining, target)
                        : remaining;
        patternTargetTypes.put(tp, target);
        return new PatternTyping(matched, subtracted);
    }

    private PatternTyping typeBindingPattern(
            BabbleParser.BindingPatternContext bp, Type domain, Type remaining) {
        if (bp.patternBindingType() == null) {
            Type bindType = remaining != Type.NOTHING ? remaining : domain;
            checker.declarationTypes().put(bp, bindType);
            return new PatternTyping(bindType, Type.NOTHING);
        }
        ScopeNode scope = checker.scopeOf(bp);
        BabbleParser.TypeContext typeCtx = bp.patternBindingType().type();
        if (bp.patternArgs() != null && isRawArrayPatternType(typeCtx.nullableType(), scope)) {
            return typeArrayDestructure(bp, null, bp.patternArgs(), domain, remaining, bp);
        }
        Type target = resolveBindingType(typeCtx, scope, bp.patternArgs() != null, bp);
        if (target == Type.ERROR) {
            checker.declarationTypes().put(bp, Type.ERROR);
            return new PatternTyping(Type.ERROR, remaining);
        }
        if (bp.patternArgs() != null) {
            return typeDestructurePattern(bp, target, bp.patternArgs(), domain, remaining, bp);
        }
        if (!TypeChecker.isUnresolved(domain)
                && domain != Type.ERROR
                && !checker.algebra().overlapsErased(domain, target)) {
            checker.warn(
                    "Impossible pattern : "
                            + domain
                            + " cannot match def "
                            + bp.name.getText()
                            + ": "
                            + target,
                    bp);
            checker.declarationTypes().put(bp, Type.ERROR);
            return new PatternTyping(Type.ERROR, remaining);
        }
        Type narrowBase =
                (remaining != Type.NOTHING && checker.algebra().overlapsErased(remaining, target))
                        ? remaining
                        : domain;
        Type matched =
                !TypeChecker.isUnresolved(narrowBase) && narrowBase != Type.ERROR
                        ? checker.algebra().narrowTarget(narrowBase, target)
                        : target;
        Type subtracted =
                !TypeChecker.isUnresolved(remaining) && remaining != Type.ERROR
                        ? checker.algebra().subtractType(remaining, target)
                        : remaining;
        checker.declarationTypes().put(bp, matched);
        patternTargetTypes.put(bp, target);
        return new PatternTyping(matched, subtracted);
    }

    // ---------------------------------------------------------------- record & sequence
    // destructuring (BIP038-I3)

    private static boolean isRawArrayPatternType(
            List<BabbleParser.NullableTypeContext> parts, ScopeNode scope) {
        if (parts.size() != 1) {
            return false;
        }
        if (!(parts.get(0) instanceof BabbleParser.NonParenTypeContext nonParen)
                || !(nonParen.nonParenNullableType()
                        instanceof BabbleParser.SimpleTypeContext simple)) {
            return false;
        }
        if (simple.OF() != null || simple.frozen != null || simple.nullMod != null) {
            return false;
        }
        List<String> path = ScopeAnalysis.simpleTypePath(simple);
        return path.size() == 1
                && "array".equals(path.get(0))
                && TypeChecker.resolveName(null, "array", scope) == null;
    }

    private PatternTyping typeDestructurePattern(
            ParserRuleContext owner,
            Type target,
            BabbleParser.PatternArgsContext args,
            Type domain,
            Type remaining,
            BabbleParser.BindingPatternContext bindCtx) {
        Type baseTarget = TypeAlgebra.unwrapFrozen(target);
        if (baseTarget instanceof Type.Array arr) {
            return typeArrayDestructure(owner, arr, args, domain, remaining, bindCtx);
        }
        if (baseTarget instanceof Type.JavaInstance ji && ji.type() == java.util.List.class) {
            return typeListDestructure(owner, target, args, domain, remaining, bindCtx);
        }
        if (baseTarget instanceof Type.Instance inst
                && inst.node().kind == ClassShape.Kind.VALUE_CLASS) {
            return typeBabbleRecordDestructure(
                    owner, target, inst, args, domain, remaining, bindCtx);
        }
        if (baseTarget instanceof Type.JavaInstance ji && ji.type().isRecord()) {
            return typeJavaRecordDestructure(owner, target, ji, args, domain, remaining, bindCtx);
        }
        checker.warn("Not a record type : " + baseTarget, owner);
        if (bindCtx != null) {
            checker.declarationTypes().put(bindCtx, Type.ERROR);
        }
        return new PatternTyping(Type.ERROR, remaining);
    }

    private PatternTyping typeBabbleRecordDestructure(
            ParserRuleContext owner,
            Type target,
            Type.Instance inst,
            BabbleParser.PatternArgsContext args,
            Type domain,
            Type remaining,
            BabbleParser.BindingPatternContext bindCtx) {
        if (!TypeChecker.isUnresolved(domain)
                && domain != Type.ERROR
                && !checker.algebra().overlapsErased(domain, target)) {
            checker.warn("Impossible pattern : " + domain + " cannot match is " + target, owner);
            if (bindCtx != null) {
                checker.declarationTypes().put(bindCtx, Type.ERROR);
            }
            return new PatternTyping(Type.ERROR, remaining);
        }
        Type narrowBase =
                (remaining != Type.NOTHING && checker.algebra().overlapsErased(remaining, target))
                        ? remaining
                        : domain;
        Type matched =
                !TypeChecker.isUnresolved(narrowBase) && narrowBase != Type.ERROR
                        ? checker.algebra().narrowTarget(narrowBase, target)
                        : target;
        if (bindCtx != null) {
            checker.declarationTypes().put(bindCtx, matched);
        }
        patternTargetTypes.put(owner, target);

        TypeNode node = inst.node();
        boolean isFrozen = matched instanceof Type.Frozen;
        Map<Type.TypeVar, Type> genericSubst = inferBabbleRecordSubst(node, inst, matched);

        if (args.restPattern() != null && args.restPattern().name != null) {
            checker.warn(
                    "Rest binding is not allowed in record pattern : use ... without def",
                    args.restPattern());
            checker.declarationTypes().put(args.restPattern(), Type.ERROR);
            return new PatternTyping(Type.ERROR, remaining);
        }

        List<String> params = node.parameters;
        Set<String> seenFields = new LinkedHashSet<>();
        List<String> fieldNames = new ArrayList<>();
        List<Integer> memberIndices = new ArrayList<>();
        List<Type> fieldTypes = new ArrayList<>();
        boolean sawNamed = false;
        int posIdx = 0;
        boolean allIrrefutable = true;
        boolean hadError = false;

        for (BabbleParser.PatternArgContext arg : args.patternArg()) {
            String fieldName;
            int memberIdx;
            if (arg.memberName() != null) {
                sawNamed = true;
                fieldName = arg.memberName().getText();
                int paramPos = params.indexOf(fieldName);
                if (paramPos < 0) {
                    checker.warn("Unknown record field : " + fieldName + " on " + node.name, arg);
                    hadError = true;
                    continue;
                }
                if (!seenFields.add(fieldName)) {
                    checker.warn("Duplicate record field : " + fieldName, arg);
                    hadError = true;
                    continue;
                }
                memberIdx = node.parameterMembers.get(paramPos);
            } else {
                if (sawNamed) {
                    checker.warn("Positional argument after named argument", arg);
                    hadError = true;
                    continue;
                }
                if (posIdx >= params.size()) {
                    checker.warn(
                            "Too many fields in record pattern : "
                                    + node.name
                                    + " has "
                                    + params.size(),
                            arg);
                    hadError = true;
                    continue;
                }
                fieldName = params.get(posIdx);
                memberIdx = node.parameterMembers.get(posIdx);
                seenFields.add(fieldName);
                posIdx++;
            }
            TypeNode.Member member = node.members.get(fieldName);
            Type rawFieldType =
                    member != null ? checker.memberType(node, member) : Type.NULLABLE_ANY;
            if (rawFieldType == Type.ERROR) {
                rawFieldType = Type.NULLABLE_ANY;
            }
            Type fieldType =
                    genericSubst.isEmpty()
                            ? rawFieldType
                            : checker.algebra().substitute(rawFieldType, genericSubst);
            if (isFrozen) {
                fieldType = Type.frozen(fieldType);
            }
            fieldNames.add(fieldName);
            memberIndices.add(memberIdx);
            fieldTypes.add(fieldType);
            PatternTyping sub = typePattern(arg.pattern(), fieldType, fieldType);
            if (sub.matchedType() == Type.ERROR) {
                hadError = true;
            }
            if (sub.subtractedType() != Type.NOTHING) {
                allIrrefutable = false;
            }
        }
        if (!sawNamed && args.restPattern() == null && !hadError && posIdx != params.size()) {
            checker.warn(
                    "Wrong number of fields in record pattern : "
                            + node.name
                            + " expects "
                            + params.size()
                            + ", got "
                            + posIdx,
                    args);
            hadError = true;
        }
        int[] indicesArr = new int[memberIndices.size()];
        for (int i = 0; i < memberIndices.size(); i++) {
            indicesArr[i] = memberIndices.get(i);
        }
        recordDestructures.put(
                owner,
                new RecordDestructureInfo(
                        false,
                        node,
                        null,
                        List.copyOf(fieldNames),
                        indicesArr,
                        List.copyOf(fieldTypes),
                        List.of(),
                        target));
        if (hadError) {
            return new PatternTyping(Type.ERROR, remaining);
        }
        Type subtracted =
                (allIrrefutable && !TypeChecker.isUnresolved(remaining) && remaining != Type.ERROR)
                        ? checker.algebra().subtractType(remaining, target)
                        : remaining;
        return new PatternTyping(matched, subtracted);
    }

    private Map<Type.TypeVar, Type> inferBabbleRecordSubst(
            TypeNode node, Type.Instance explicitInst, Type matched) {
        List<Type.TypeVar> tVars = checker.annotator().typeVarsOf(node);
        if (tVars.isEmpty()) {
            return Map.of();
        }
        Map<Type.TypeVar, Type> subst = new HashMap<>();
        List<Type.Instance> matchingInsts = new ArrayList<>();
        for (Type m : Type.membersOf(TypeAlgebra.unwrapFrozen(matched))) {
            Type um = TypeAlgebra.unwrapFrozen(m);
            if (um instanceof Type.Instance mi
                    && mi.node() == node
                    && mi.typeArguments().size() == tVars.size()) {
                matchingInsts.add(mi);
            }
        }
        if (matchingInsts.isEmpty() && explicitInst.typeArguments().size() == tVars.size()) {
            matchingInsts.add(explicitInst);
        }
        for (int i = 0; i < tVars.size(); i++) {
            Type arg;
            if (matchingInsts.isEmpty()) {
                arg = tVars.get(i).effectiveBound();
            } else {
                List<Type> parts = new ArrayList<>(matchingInsts.size());
                for (Type.Instance mi : matchingInsts) {
                    parts.add(mi.typeArguments().get(i));
                }
                arg = Type.union(parts);
            }
            subst.put(tVars.get(i), arg);
        }
        return subst;
    }

    private PatternTyping typeJavaRecordDestructure(
            ParserRuleContext owner,
            Type target,
            Type.JavaInstance ji,
            BabbleParser.PatternArgsContext args,
            Type domain,
            Type remaining,
            BabbleParser.BindingPatternContext bindCtx) {
        if (!TypeChecker.isUnresolved(domain)
                && domain != Type.ERROR
                && !checker.algebra().overlapsErased(domain, target)) {
            checker.warn("Impossible pattern : " + domain + " cannot match is " + target, owner);
            if (bindCtx != null) {
                checker.declarationTypes().put(bindCtx, Type.ERROR);
            }
            return new PatternTyping(Type.ERROR, remaining);
        }
        Type narrowBase =
                (remaining != Type.NOTHING && checker.algebra().overlapsErased(remaining, target))
                        ? remaining
                        : domain;
        Type matched =
                !TypeChecker.isUnresolved(narrowBase) && narrowBase != Type.ERROR
                        ? checker.algebra().narrowTarget(narrowBase, target)
                        : target;
        if (bindCtx != null) {
            checker.declarationTypes().put(bindCtx, matched);
        }
        patternTargetTypes.put(owner, target);

        Class<?> recClass = ji.type();
        boolean isFrozen = matched instanceof Type.Frozen;
        Map<TypeVariable<?>, Type> javaEnv = inferJavaRecordEnv(recClass, ji, matched);

        if (args.restPattern() != null && args.restPattern().name != null) {
            checker.warn(
                    "Rest binding is not allowed in record pattern : use ... without def",
                    args.restPattern());
            checker.declarationTypes().put(args.restPattern(), Type.ERROR);
            return new PatternTyping(Type.ERROR, remaining);
        }

        RecordComponent[] comps = recClass.getRecordComponents();
        Map<String, RecordComponent> byName = new HashMap<>();
        for (RecordComponent rc : comps) {
            byName.put(rc.getName(), rc);
        }

        Set<String> seenFields = new LinkedHashSet<>();
        List<String> fieldNames = new ArrayList<>();
        List<Type> fieldTypes = new ArrayList<>();
        List<Method> accessors = new ArrayList<>();
        boolean sawNamed = false;
        int posIdx = 0;
        boolean allIrrefutable = true;
        boolean hadError = false;

        for (BabbleParser.PatternArgContext arg : args.patternArg()) {
            RecordComponent comp;
            if (arg.memberName() != null) {
                sawNamed = true;
                String fn = arg.memberName().getText();
                comp = byName.get(fn);
                if (comp == null) {
                    checker.warn(
                            "Unknown record field : " + fn + " on " + recClass.getSimpleName(),
                            arg);
                    hadError = true;
                    continue;
                }
                if (!seenFields.add(fn)) {
                    checker.warn("Duplicate record field : " + fn, arg);
                    hadError = true;
                    continue;
                }
            } else {
                if (sawNamed) {
                    checker.warn("Positional argument after named argument", arg);
                    hadError = true;
                    continue;
                }
                if (posIdx >= comps.length) {
                    checker.warn(
                            "Too many fields in record pattern : "
                                    + recClass.getSimpleName()
                                    + " has "
                                    + comps.length,
                            arg);
                    hadError = true;
                    continue;
                }
                comp = comps[posIdx++];
                seenFields.add(comp.getName());
            }
            Method acc = comp.getAccessor();
            Type compType =
                    JavaTypeBridge.fromJavaType(
                            comp.getGenericType(),
                            comp.getAnnotatedType(),
                            javaEnv,
                            true,
                            false,
                            JavaNullability.Nullness.UNSPECIFIED,
                            false);
            if (isFrozen) {
                compType = Type.frozen(compType);
            }
            fieldNames.add(comp.getName());
            fieldTypes.add(compType);
            accessors.add(acc);
            PatternTyping sub = typePattern(arg.pattern(), compType, compType);
            if (sub.matchedType() == Type.ERROR) {
                hadError = true;
            }
            if (sub.subtractedType() != Type.NOTHING) {
                allIrrefutable = false;
            }
        }
        if (!sawNamed && args.restPattern() == null && !hadError && posIdx != comps.length) {
            checker.warn(
                    "Wrong number of fields in record pattern : "
                            + recClass.getSimpleName()
                            + " expects "
                            + comps.length
                            + ", got "
                            + posIdx,
                    args);
            hadError = true;
        }
        recordDestructures.put(
                owner,
                new RecordDestructureInfo(
                        true,
                        null,
                        recClass,
                        List.copyOf(fieldNames),
                        new int[0],
                        List.copyOf(fieldTypes),
                        List.copyOf(accessors),
                        target));
        if (hadError) {
            return new PatternTyping(Type.ERROR, remaining);
        }
        Type subtracted =
                (allIrrefutable && !TypeChecker.isUnresolved(remaining) && remaining != Type.ERROR)
                        ? checker.algebra().subtractType(remaining, target)
                        : remaining;
        return new PatternTyping(matched, subtracted);
    }

    private Map<TypeVariable<?>, Type> inferJavaRecordEnv(
            Class<?> recClass, Type.JavaInstance explicitJi, Type matched) {
        TypeVariable<?>[] tps = recClass.getTypeParameters();
        if (tps.length == 0) {
            return Map.of();
        }
        Map<TypeVariable<?>, Type> env = new HashMap<>();
        List<Type.JavaInstance> matchingJis = new ArrayList<>();
        for (Type m : Type.membersOf(TypeAlgebra.unwrapFrozen(matched))) {
            Type um = TypeAlgebra.unwrapFrozen(m);
            if (um instanceof Type.JavaInstance mji
                    && mji.type() == recClass
                    && mji.typeArguments().size() == tps.length) {
                matchingJis.add(mji);
            }
        }
        if (matchingJis.isEmpty() && explicitJi.typeArguments().size() == tps.length) {
            matchingJis.add(explicitJi);
        }
        for (int i = 0; i < tps.length; i++) {
            if (matchingJis.isEmpty()) {
                env.put(tps[i], Type.NULLABLE_ANY);
            } else {
                List<Type> parts = new ArrayList<>(matchingJis.size());
                for (Type.JavaInstance mji : matchingJis) {
                    parts.add(mji.typeArguments().get(i));
                }
                env.put(tps[i], Type.union(parts));
            }
        }
        return env;
    }

    private PatternTyping typeListDestructure(
            ParserRuleContext owner,
            Type target,
            BabbleParser.PatternArgsContext args,
            Type domain,
            Type remaining,
            BabbleParser.BindingPatternContext bindCtx) {
        if (!TypeChecker.isUnresolved(domain)
                && domain != Type.ERROR
                && !checker.algebra().overlapsErased(domain, target)) {
            checker.warn("Impossible pattern : " + domain + " cannot match is " + target, owner);
            if (bindCtx != null) {
                checker.declarationTypes().put(bindCtx, Type.ERROR);
            }
            return new PatternTyping(Type.ERROR, remaining);
        }
        Type narrowBase =
                (remaining != Type.NOTHING && checker.algebra().overlapsErased(remaining, target))
                        ? remaining
                        : domain;
        Type matched =
                !TypeChecker.isUnresolved(narrowBase) && narrowBase != Type.ERROR
                        ? checker.algebra().narrowTarget(narrowBase, target)
                        : target;
        if (bindCtx != null) {
            checker.declarationTypes().put(bindCtx, matched);
        }
        patternTargetTypes.put(owner, target);

        boolean isFrozen = matched instanceof Type.Frozen;
        Type elemType = inferListElementType(matched);
        if (isFrozen) {
            elemType = Type.frozen(elemType);
        }

        boolean hadError = false;
        for (BabbleParser.PatternArgContext arg : args.patternArg()) {
            if (arg.memberName() != null) {
                checker.warn("Named argument not allowed in sequence pattern", arg);
                hadError = true;
                continue;
            }
            if (typePattern(arg.pattern(), elemType, elemType).matchedType() == Type.ERROR) {
                hadError = true;
            }
        }
        boolean hasRest = args.restPattern() != null;
        if (hasRest && args.restPattern().name != null) {
            checker.declarationTypes()
                    .put(
                            args.restPattern(),
                            new Type.JavaInstance(java.util.List.class, List.of(elemType)));
        }
        int fixedCount = args.patternArg().size();
        sequenceDestructures.put(
                owner,
                new SequenceDestructureInfo(false, null, elemType, fixedCount, hasRest, target));
        if (hadError) {
            return new PatternTyping(Type.ERROR, remaining);
        }
        Type subtracted =
                (fixedCount == 0
                                && hasRest
                                && !TypeChecker.isUnresolved(remaining)
                                && remaining != Type.ERROR)
                        ? checker.algebra().subtractType(remaining, target)
                        : remaining;
        return new PatternTyping(matched, subtracted);
    }

    private Type inferListElementType(Type matched) {
        List<Type> elems = new ArrayList<>();
        for (Type m : Type.membersOf(TypeAlgebra.unwrapFrozen(matched))) {
            Type um = TypeAlgebra.unwrapFrozen(m);
            Type projected = checker.algebra().projectToJava(um, java.util.List.class);
            if (projected instanceof Type.JavaInstance ji && !ji.typeArguments().isEmpty()) {
                elems.add(ji.typeArguments().get(0));
            }
        }
        return elems.isEmpty() ? Type.NULLABLE_ANY : Type.union(elems);
    }

    private PatternTyping typeArrayDestructure(
            ParserRuleContext owner,
            Type.Array explicitArr,
            BabbleParser.PatternArgsContext args,
            Type domain,
            Type remaining,
            BabbleParser.BindingPatternContext bindCtx) {
        Type matched;
        Type target;
        if (explicitArr != null) {
            target = explicitArr;
            if (!TypeChecker.isUnresolved(domain)
                    && domain != Type.ERROR
                    && !checker.algebra().overlapsErased(domain, target)) {
                checker.warn(
                        "Impossible pattern : " + domain + " cannot match is " + target, owner);
                if (bindCtx != null) {
                    checker.declarationTypes().put(bindCtx, Type.ERROR);
                }
                return new PatternTyping(Type.ERROR, remaining);
            }
            Type narrowBase =
                    (remaining != Type.NOTHING
                                    && checker.algebra().overlapsErased(remaining, target))
                            ? remaining
                            : domain;
            matched =
                    !TypeChecker.isUnresolved(narrowBase) && narrowBase != Type.ERROR
                            ? checker.algebra().narrowTarget(narrowBase, target)
                            : target;
            patternTargetTypes.put(owner, target);
        } else {
            // Bare `is array(...)`: narrow `remaining` (or `domain`) to all Type.Array members.
            Type active = remaining != Type.NOTHING ? remaining : domain;
            List<Type> arrMembers = new ArrayList<>();
            boolean hasAny = false;
            for (Type m : Type.membersOf(domain)) {
                Type um = TypeAlgebra.unwrapFrozen(m);
                if (um instanceof Type.Array) {
                    arrMembers.add(m);
                } else if (um == Type.ANY || um instanceof Type.TypeVar) {
                    hasAny = true;
                }
            }
            if (arrMembers.isEmpty()
                    && !hasAny
                    && !TypeChecker.isUnresolved(domain)
                    && domain != Type.ERROR) {
                checker.warn("Impossible pattern : " + domain + " cannot match is array", owner);
                if (bindCtx != null) {
                    checker.declarationTypes().put(bindCtx, Type.ERROR);
                }
                return new PatternTyping(Type.ERROR, remaining);
            }
            List<Type> remArrMembers = new ArrayList<>();
            for (Type m : Type.membersOf(active)) {
                if (TypeAlgebra.unwrapFrozen(m) instanceof Type.Array) {
                    remArrMembers.add(m);
                }
            }
            List<Type> chosen = !remArrMembers.isEmpty() ? remArrMembers : arrMembers;
            if (!chosen.isEmpty()) {
                matched = Type.union(chosen);
            } else {
                matched = active instanceof Type.Frozen ? Type.frozen(Type.ANY) : Type.ANY;
            }
            target = chosen.size() == 1 ? TypeAlgebra.unwrapFrozen(chosen.get(0)) : null;
            if (target != null) {
                patternTargetTypes.put(owner, target);
            }
        }
        if (bindCtx != null) {
            checker.declarationTypes().put(bindCtx, matched);
        }

        boolean isFrozen = matched instanceof Type.Frozen;
        List<Type> elemTypes = new ArrayList<>();
        Class<?> arrayClass = null;
        int concreteArrCount = 0;
        for (Type m : Type.membersOf(TypeAlgebra.unwrapFrozen(matched))) {
            Type um = TypeAlgebra.unwrapFrozen(m);
            if (um instanceof Type.Array a) {
                elemTypes.add(a.element());
                Class<?> checked = JavaTypeBridge.checkedArrayClass(a);
                if (JavaTypeBridge.isBytecodeAccessible(checked)) {
                    arrayClass = checked;
                }
                concreteArrCount++;
            }
        }
        if (concreteArrCount != 1) {
            arrayClass = null;
        }
        Type elemType = elemTypes.isEmpty() ? Type.NULLABLE_ANY : Type.union(elemTypes);
        if (isFrozen) {
            elemType = Type.frozen(elemType);
        }

        boolean hadError = false;
        for (BabbleParser.PatternArgContext arg : args.patternArg()) {
            if (arg.memberName() != null) {
                checker.warn("Named argument not allowed in sequence pattern", arg);
                hadError = true;
                continue;
            }
            if (typePattern(arg.pattern(), elemType, elemType).matchedType() == Type.ERROR) {
                hadError = true;
            }
        }
        boolean hasRest = args.restPattern() != null;
        if (hasRest && args.restPattern().name != null) {
            Type restType;
            if (concreteArrCount == 1) {
                restType = new Type.Array(elemType);
            } else if (concreteArrCount > 1) {
                List<Type> restParts = new ArrayList<>();
                for (Type e : elemTypes) {
                    restParts.add(new Type.Array(isFrozen ? Type.frozen(e) : e));
                }
                restType = Type.union(restParts);
            } else {
                restType = Type.ANY;
            }
            checker.declarationTypes().put(args.restPattern(), restType);
        }
        int fixedCount = args.patternArg().size();
        sequenceDestructures.put(
                owner,
                new SequenceDestructureInfo(
                        true, arrayClass, elemType, fixedCount, hasRest, target));
        if (hadError) {
            return new PatternTyping(Type.ERROR, remaining);
        }
        Type subtracted = remaining;
        if (fixedCount == 0
                && hasRest
                && !TypeChecker.isUnresolved(remaining)
                && remaining != Type.ERROR) {
            if (target != null) {
                subtracted = checker.algebra().subtractType(remaining, target);
            } else {
                List<Type> remList = new ArrayList<>();
                for (Type m : Type.membersOf(remaining)) {
                    if (!(TypeAlgebra.unwrapFrozen(m) instanceof Type.Array)) {
                        remList.add(m);
                    }
                }
                subtracted = remList.isEmpty() ? Type.NOTHING : Type.union(remList);
            }
        }
        return new PatternTyping(matched, subtracted);
    }

    private Type resolvePatternType(
            List<BabbleParser.NullableTypeContext> parts,
            ParserRuleContext at,
            ScopeNode scope,
            boolean hasArgs,
            ParserRuleContext owner) {
        BabbleParser.SimpleTypeContext singleSimple = null;
        if (parts.size() == 1
                && parts.get(0) instanceof BabbleParser.NonParenTypeContext nonParen
                && nonParen.nonParenNullableType()
                        instanceof BabbleParser.SimpleTypeContext simple) {
            singleSimple = simple;
            if (!checkSingleSimpleType(simple, scope, hasArgs)) {
                return Type.ERROR;
            }
        }
        Type target = checker.annotator().groupAnnotation(parts, scope, true);
        if (!validateIsTargetType(target, at, hasArgs)) {
            return Type.ERROR;
        }
        recordBuiltinIfSimple(owner, singleSimple);
        return target;
    }

    private Type resolveBindingType(
            BabbleParser.TypeContext typeCtx,
            ScopeNode scope,
            boolean hasArgs,
            ParserRuleContext owner) {
        BabbleParser.SimpleTypeContext singleSimple = null;
        if (typeCtx.nullableType().size() == 1
                && typeCtx.nullableType(0) instanceof BabbleParser.NonParenTypeContext nonParen
                && nonParen.nonParenNullableType()
                        instanceof BabbleParser.SimpleTypeContext simple) {
            singleSimple = simple;
            if (!checkSingleSimpleType(simple, scope, hasArgs)) {
                return Type.ERROR;
            }
        }
        Type target = checker.annotator().annotation(typeCtx, scope, true);
        if (!validateIsTargetType(target, typeCtx, hasArgs)) {
            return Type.ERROR;
        }
        recordBuiltinIfSimple(owner, singleSimple);
        return target;
    }

    private boolean checkSingleSimpleType(
            BabbleParser.SimpleTypeContext simple, ScopeNode scope, boolean hasArgs) {
        String name = simple.typeName().getText();
        if ("void".equals(name)) {
            checker.warn("Type error : cannot test 'is void'", simple);
            return false;
        }
        if (simple.frozen != null) {
            checker.warn("Cannot test 'frozen' with 'is'", simple);
            return false;
        }
        if (!hasArgs
                && simple.OF() != null
                && !"array".equals(name)
                && !checker.annotator().resolvesToArrayAlias(simple, scope)) {
            checker.warn(
                    "Cannot test erased type arguments with 'is' : use 'is " + name + "'", simple);
            return false;
        }
        return true;
    }

    private boolean validateIsTargetType(Type target, ParserRuleContext at, boolean hasArgs) {
        if (target == Type.ERROR) {
            return false;
        }
        if (target.anyMatch(Type.Frozen.class::isInstance)) {
            checker.warn("Cannot test 'frozen' with 'is'", at);
            return false;
        }
        if (target.hasTypeVar()) {
            checker.warn("Cannot test type parameter with 'is' : " + target, at);
            return false;
        }
        if (TypeAlgebra.containsFunction(target)) {
            checker.warn("Cannot test a function type with 'is'", at);
            return false;
        }
        if (!hasArgs) {
            String erasedGeneric = TypeCheckDiagnostics.findErasedGenericName(target);
            if (erasedGeneric != null) {
                checker.warn(
                        "Cannot test erased type arguments with 'is' : use 'is "
                                + erasedGeneric
                                + "'",
                        at);
                return false;
            }
        }
        return true;
    }

    private void recordBuiltinIfSimple(
            ParserRuleContext owner, BabbleParser.SimpleTypeContext simple) {
        if (simple != null
                && simple.typeName().coreTypeKeyword() != null
                && (simple.nullMod == null || simple.nullMod.getType() != BabbleLexer.QUESTION)
                && simple.OF() == null) {
            patternBuiltins.put(owner, simple.typeName().getText());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Type expectedForValuePattern(Type remaining) {
        if (remaining == null || remaining == Type.ERROR || remaining == Type.NOTHING) {
            return null;
        }
        Type base = Type.withoutNull(remaining);
        return Type.isNumericScalar(base) ? base : null;
    }

    private boolean overlapsValue(Type remaining, Type patType) {
        if (remaining == Type.ERROR
                || patType == Type.ERROR
                || remaining == Type.NOTHING
                || patType == Type.NOTHING) {
            return true;
        }
        if (patType == Type.NULL) {
            return Type.containsNull(remaining)
                    || remaining == Type.ANY
                    || remaining instanceof Type.TypeVar;
        }
        for (Type rm : Type.membersOf(remaining)) {
            for (Type pm : Type.membersOf(patType)) {
                if (Type.isNumericScalar(rm) && Type.isNumericScalar(pm)) {
                    return true;
                }
            }
        }
        return checker.algebra().overlapsErased(remaining, patType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LoweringTargets.PatternConst resolvePatternConst(
            BabbleParser.ExpressionContext expr, Type patType) {
        BabbleParser.ExpressionContext cur = unwrapSingleParen(expr);
        Object resolved = checker.resolvedLiterals().get(cur);
        if (resolved != null) {
            return new LoweringTargets.PatternConst(
                    resolved, null, null, PrimitiveKind.of(patType));
        }
        if (cur instanceof BabbleParser.NullContext) {
            return new LoweringTargets.PatternConst(null, null, null, PrimitiveKind.NONE);
        }
        if (cur instanceof BabbleParser.BooleanContext b) {
            return new LoweringTargets.PatternConst(
                    Boolean.valueOf(b.getText()), null, null, PrimitiveKind.BOOL);
        }
        if (cur instanceof BabbleParser.IntegerContext lit) {
            try {
                return new LoweringTargets.PatternConst(
                        Integer.parseInt(lit.getText()), null, null, PrimitiveKind.INT);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (cur instanceof BabbleParser.LongLiteralContext lit) {
            try {
                String t = lit.getText();
                return new LoweringTargets.PatternConst(
                        Long.parseLong(t.substring(0, t.length() - 1)),
                        null,
                        null,
                        PrimitiveKind.LONG);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (cur instanceof BabbleParser.FloatLiteralContext lit) {
            try {
                String t = lit.getText();
                return new LoweringTargets.PatternConst(
                        Float.parseFloat(t.substring(0, t.length() - 1)),
                        null,
                        null,
                        PrimitiveKind.FLOAT);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (cur instanceof BabbleParser.DoubleContext lit) {
            try {
                return new LoweringTargets.PatternConst(
                        Double.parseDouble(lit.getText()), null, null, PrimitiveKind.DOUBLE);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (cur instanceof BabbleParser.CharLiteralContext lit) {
            return new LoweringTargets.PatternConst(
                    StringLiterals.decodeChar(lit.getText()), null, null, PrimitiveKind.CHAR);
        }
        if (cur instanceof BabbleParser.StringContext lit) {
            return new LoweringTargets.PatternConst(
                    StringLiterals.decode(lit), null, null, PrimitiveKind.NONE);
        }
        if (cur instanceof BabbleParser.NegateContext neg) {
            Object negResolved = checker.resolvedLiterals().get(neg);
            if (negResolved != null) {
                return new LoweringTargets.PatternConst(
                        negResolved, null, null, PrimitiveKind.of(patType));
            }
            LoweringTargets.PatternConst inner = resolvePatternConst(neg.operand, patType);
            if (inner != null && inner.value() instanceof Number n) {
                Object negated =
                        switch (n) {
                            case Byte b -> (byte) -b;
                            case Short s -> (short) -s;
                            case Integer i -> -i;
                            case Long l -> -l;
                            case Float f -> -f;
                            case Double d -> -d;
                            default -> null;
                        };
                if (negated != null) {
                    return new LoweringTargets.PatternConst(negated, null, null, inner.kind());
                }
            }
            return null;
        }
        if (cur instanceof BabbleParser.SelectorContext sel
                && sel.expression() != null
                && sel.safe == null) {
            Type recvType = checker.types().get(sel.expression());
            if (recvType instanceof Type.JavaClassValue jcv && jcv.type().isEnum()) {
                String constName = ScopeAnalysis.selectorName(sel);
                try {
                    Enum<?> enumVal = Enum.valueOf((Class<Enum>) jcv.type(), constName);
                    return new LoweringTargets.PatternConst(
                            enumVal, jcv.type(), constName, PrimitiveKind.NONE);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- exhaustiveness pattern
    // translation (BIP038-I4)

    private List<Type> recordFieldTypesForExhaustiveness(Type recordType) {
        boolean isFrozen = recordType instanceof Type.Frozen;
        Type base = TypeAlgebra.unwrapFrozen(recordType);
        if (base instanceof Type.Instance inst && inst.node().kind == ClassShape.Kind.VALUE_CLASS) {
            TypeNode node = inst.node();
            Map<Type.TypeVar, Type> subst = inferBabbleRecordSubst(node, inst, recordType);
            List<Type> out = new ArrayList<>(node.parameters.size());
            for (String p : node.parameters) {
                TypeNode.Member m = node.members.get(p);
                Type raw = m != null ? checker.memberType(node, m) : Type.NULLABLE_ANY;
                if (raw == Type.ERROR) {
                    raw = Type.NULLABLE_ANY;
                }
                Type ft = subst.isEmpty() ? raw : checker.algebra().substitute(raw, subst);
                out.add(isFrozen ? Type.frozen(ft) : ft);
            }
            return out;
        }
        if (base instanceof Type.JavaInstance ji && ji.type().isRecord()) {
            Map<TypeVariable<?>, Type> jenv = inferJavaRecordEnv(ji.type(), ji, recordType);
            RecordComponent[] comps = ji.type().getRecordComponents();
            List<Type> out = new ArrayList<>(comps.length);
            for (RecordComponent rc : comps) {
                Type ft =
                        JavaTypeBridge.fromJavaType(
                                rc.getGenericType(),
                                rc.getAnnotatedType(),
                                jenv,
                                true,
                                false,
                                JavaNullability.Nullness.UNSPECIFIED,
                                false);
                out.add(isFrozen ? Type.frozen(ft) : ft);
            }
            return out;
        }
        return List.of();
    }

    private MatchExhaustiveness.Pat toExhaustivenessPat(BabbleParser.PatternContext pat) {
        List<BabbleParser.AtomicPatternContext> atoms = pat.atomicPattern();
        if (atoms.size() == 1) {
            return toExhaustivenessAtom(atoms.get(0));
        }
        List<MatchExhaustiveness.Pat> alts = new ArrayList<>(atoms.size());
        for (BabbleParser.AtomicPatternContext a : atoms) {
            alts.add(toExhaustivenessAtom(a));
        }
        return new MatchExhaustiveness.Pat.Or(List.copyOf(alts));
    }

    private MatchExhaustiveness.Pat toExhaustivenessAtom(BabbleParser.AtomicPatternContext atom) {
        return switch (atom) {
            case BabbleParser.ValuePatternContext vp -> {
                if (BlockScopes.isWildcardExpr(vp.expr)) {
                    yield MatchExhaustiveness.Pat.Wildcard.INSTANCE;
                }
                LoweringMetadata.RangePatternSource rps = rangePatterns.get(vp);
                if (rps != null) {
                    yield toExhaustivenessRange(rps);
                }
                LoweringTargets.PatternConst c = patternConsts.get(vp);
                if (c != null) {
                    if (c.isNull()) {
                        yield MatchExhaustiveness.Pat.NullConst.INSTANCE;
                    }
                    if (c.isEnum()) {
                        yield new MatchExhaustiveness.Pat.EnumConst(
                                c.enumClass(), c.enumConstantName());
                    }
                    if (c.value() instanceof Boolean b) {
                        yield b
                                ? MatchExhaustiveness.Pat.BoolConst.TRUE
                                : MatchExhaustiveness.Pat.BoolConst.FALSE;
                    }
                    yield new MatchExhaustiveness.Pat.ScalarConst(c.value(), c.kind());
                }
                yield MatchExhaustiveness.Pat.Opaque.INSTANCE;
            }
            case BabbleParser.RelationalPatternContext ignored ->
                    MatchExhaustiveness.Pat.Opaque.INSTANCE;
            case BabbleParser.MembershipPatternContext ignored ->
                    MatchExhaustiveness.Pat.Opaque.INSTANCE;
            case BabbleParser.TypeOrDestructurePatternContext tp ->
                    toExhaustivenessTypedOrDestructure(tp, tp.patternArgs());
            case BabbleParser.BindingPatternContext bp -> {
                if (bp.patternBindingType() == null) {
                    yield MatchExhaustiveness.Pat.Wildcard.INSTANCE;
                }
                yield toExhaustivenessTypedOrDestructure(bp, bp.patternArgs());
            }
            default -> MatchExhaustiveness.Pat.Opaque.INSTANCE;
        };
    }

    private MatchExhaustiveness.Pat toExhaustivenessTypedOrDestructure(
            ParserRuleContext owner, BabbleParser.PatternArgsContext args) {
        RecordDestructureInfo rec = recordDestructures.get(owner);
        if (rec != null && args != null) {
            int arity =
                    rec.isJavaRecord()
                            ? rec.javaRecordClass().getRecordComponents().length
                            : rec.babbleNode().parameters.size();
            MatchExhaustiveness.Pat[] canonical = new MatchExhaustiveness.Pat[arity];
            java.util.Arrays.fill(canonical, MatchExhaustiveness.Pat.Wildcard.INSTANCE);
            List<BabbleParser.PatternArgContext> argList = args.patternArg();
            if (argList.size() == rec.fieldNames().size()) {
                for (int i = 0; i < argList.size(); i++) {
                    String fn = rec.fieldNames().get(i);
                    int canonicalIdx = -1;
                    if (rec.isJavaRecord()) {
                        RecordComponent[] comps = rec.javaRecordClass().getRecordComponents();
                        for (int k = 0; k < comps.length; k++) {
                            if (comps[k].getName().equals(fn)) {
                                canonicalIdx = k;
                                break;
                            }
                        }
                    } else {
                        canonicalIdx = rec.babbleNode().parameters.indexOf(fn);
                    }
                    if (canonicalIdx >= 0 && canonicalIdx < arity) {
                        canonical[canonicalIdx] = toExhaustivenessPat(argList.get(i).pattern());
                    }
                }
            }
            return new MatchExhaustiveness.Pat.RecordPat(
                    rec.targetType(),
                    rec.isJavaRecord(),
                    rec.babbleNode(),
                    rec.javaRecordClass(),
                    List.of(canonical));
        }
        SequenceDestructureInfo seq = sequenceDestructures.get(owner);
        if (seq != null && args != null) {
            List<MatchExhaustiveness.Pat> elems = new ArrayList<>(args.patternArg().size());
            for (BabbleParser.PatternArgContext arg : args.patternArg()) {
                elems.add(toExhaustivenessPat(arg.pattern()));
            }
            return new MatchExhaustiveness.Pat.SeqPat(
                    seq.isArray(), seq.targetType(), List.copyOf(elems), seq.hasRest());
        }
        Type target = patternTargetTypes.get(owner);
        return target != null
                ? new MatchExhaustiveness.Pat.TypePat(target)
                : MatchExhaustiveness.Pat.Opaque.INSTANCE;
    }

    private MatchExhaustiveness.Pat toExhaustivenessRange(LoweringMetadata.RangePatternSource rps) {
        if (rps.stringElement()) {
            return MatchExhaustiveness.Pat.Opaque.INSTANCE;
        }
        Type loType = checker.types().get(rps.lo());
        Type hiType = checker.types().get(rps.hi());
        LoweringTargets.PatternConst loC =
                loType != null ? resolvePatternConst(rps.lo(), loType) : null;
        LoweringTargets.PatternConst hiC =
                hiType != null ? resolvePatternConst(rps.hi(), hiType) : null;
        if (loC == null || hiC == null || loC.value() == null || hiC.value() == null) {
            return MatchExhaustiveness.Pat.Opaque.INSTANCE;
        }
        long step = 1L;
        if (rps.step() != null) {
            Type st = checker.types().get(rps.step());
            LoweringTargets.PatternConst stC =
                    st != null ? resolvePatternConst(rps.step(), st) : null;
            if (stC == null || !(stC.value() instanceof Number sn)) {
                return MatchExhaustiveness.Pat.Opaque.INSTANCE;
            }
            step = sn.longValue();
        }
        if (loC.value() instanceof Character lc && hiC.value() instanceof Character hc) {
            return new MatchExhaustiveness.Pat.ConstRange(
                    lc, hc, rps.inclusive(), step, true, false, 0, 0);
        }
        if (loC.value() instanceof Number ln && hiC.value() instanceof Number hn) {
            if (rps.elemKind() == PrimitiveKind.FLOAT
                    || rps.elemKind() == PrimitiveKind.DOUBLE
                    || ln instanceof Float
                    || ln instanceof Double
                    || hn instanceof Float
                    || hn instanceof Double) {
                return new MatchExhaustiveness.Pat.ConstRange(
                        0, 0, rps.inclusive(), 1L, false, true, ln.doubleValue(), hn.doubleValue());
            }
            return new MatchExhaustiveness.Pat.ConstRange(
                    ln.longValue(), hn.longValue(), rps.inclusive(), step, false, false, 0, 0);
        }
        return MatchExhaustiveness.Pat.Opaque.INSTANCE;
    }
}
