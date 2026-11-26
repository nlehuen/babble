package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.parser.StringLiterals;
import org.babblelang.runtime.Range;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Type-checking, narrowing, and lowering fact collection for {@code match} expressions
 * ({@code BIP038}). Collaborator of {@link TypeChecker}.
 */
final class MatchTyper {
    private final TypeChecker checker;
    private final Map<ParserRuleContext, Type> patternTargetTypes = new IdentityHashMap<>();
    private final Map<ParserRuleContext, String> patternBuiltins = new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.PatternConst> patternConsts = new IdentityHashMap<>();
    private final Map<BabbleParser.ValuePatternContext, LoweringMetadata.RangePatternSource> rangePatterns = new IdentityHashMap<>();
    private final Map<BabbleParser.MembershipPatternContext, LoweringTargets.MembershipTarget> membershipTargets = new IdentityHashMap<>();
    private final Map<BabbleParser.RelationalPatternContext, PrimitiveKind> relationalKinds = new IdentityHashMap<>();
    private final Map<BabbleParser.MatchExpressionContext, Boolean> matchExhaustive = new IdentityHashMap<>();

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
    }

    void clearSubtree(ParserRuleContext ctx) {
        patternTargetTypes.remove(ctx);
        patternBuiltins.remove(ctx);
        patternConsts.remove(ctx);
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

    Map<BabbleParser.MembershipPatternContext, LoweringTargets.MembershipTarget> membershipTargets() {
        return membershipTargets;
    }

    Map<BabbleParser.RelationalPatternContext, PrimitiveKind> relationalKinds() {
        return relationalKinds;
    }

    Map<BabbleParser.MatchExpressionContext, Boolean> matchExhaustive() {
        return matchExhaustive;
    }

    private record PatternTyping(Type matchedType, Type subtractedType) {
    }

    Type typeMatch(BabbleParser.MatchExpressionContext ctx, Type expected, TypeChecker.Role role) {
        Type scrutineeType = checker.synth(ctx.scrutinee);
        ParserRuleContext scrutineeDecl = checker.narrowing().narrowableDeclaration(ctx.scrutinee);
        TypeEnv baseEnv = checker.env();
        TypeEnv fallthroughEnv = baseEnv;
        Type remaining = scrutineeType;

        List<Type> branchTypes = new ArrayList<>();
        List<ParserRuleContext> branchNodes = new ArrayList<>();
        List<TypeEnv> branchEnvs = new ArrayList<>();

        for (BabbleParser.CaseClauseContext clause : ctx.caseClause()) {
            checker.setEnv(fallthroughEnv);
            PatternTyping pt = typePattern(clause.pattern(), remaining);

            TypeEnv armEnv = fallthroughEnv;
            if (scrutineeDecl != null && pt.matchedType() != Type.ERROR && pt.matchedType() != Type.NOTHING
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
                if (scrutineeDecl != null && nextRemaining != Type.NOTHING && nextRemaining != Type.ERROR
                        && !nextRemaining.equals(remaining)) {
                    fallthroughEnv = fallthroughEnv.apply(Map.of(scrutineeDecl, nextRemaining));
                }
                remaining = nextRemaining;
            }
        }

        if (ctx.elseClause() != null) {
            BabbleParser.ElseClauseContext ec = ctx.elseClause();
            if (ec.name != null) {
                Type elseBindingType = (remaining == Type.NOTHING || remaining == Type.ERROR)
                        ? scrutineeType : remaining;
                checker.declarationTypes().put(ec, elseBindingType);
            }
            checker.setEnv(fallthroughEnv);
            Type elseType = checker.type(ec.elseBlock, expected, role);
            branchTypes.add(elseType);
            branchNodes.add(ec.elseBlock);
            branchEnvs.add(checker.env());
            matchExhaustive.put(ctx, true);
        } else {
            matchExhaustive.put(ctx, remaining == Type.NOTHING);
        }

        TypeEnv joinedEnv = null;
        for (int i = 0; i < branchTypes.size(); i++) {
            if (branchTypes.get(i) != Type.NOTHING) {
                joinedEnv = joinedEnv == null
                        ? branchEnvs.get(i)
                        : TypeEnv.joinBranches(baseEnv, joinedEnv, branchEnvs.get(i));
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

    private PatternTyping typePattern(BabbleParser.PatternContext pat, Type remaining) {
        List<BabbleParser.AtomicPatternContext> atoms = pat.atomicPattern();
        if (atoms.size() == 1) {
            return typeAtomicPattern(atoms.get(0), remaining);
        }
        List<Type> matched = new ArrayList<>(atoms.size());
        Type curRem = remaining;
        for (BabbleParser.AtomicPatternContext atom : atoms) {
            PatternTyping pt = typeAtomicPattern(atom, curRem);
            if (pt.matchedType() != Type.ERROR && pt.matchedType() != Type.NOTHING) {
                matched.add(pt.matchedType());
            }
            if (pt.subtractedType() != null) {
                curRem = pt.subtractedType();
            }
        }
        Type unionMatched = matched.isEmpty() ? remaining : Type.union(matched);
        return new PatternTyping(unionMatched, curRem);
    }

    private PatternTyping typeAtomicPattern(BabbleParser.AtomicPatternContext atom, Type remaining) {
        return switch (atom) {
            case BabbleParser.ValuePatternContext vp -> typeValuePattern(vp, remaining);
            case BabbleParser.RelationalPatternContext rp -> typeRelationalPattern(rp, remaining);
            case BabbleParser.MembershipPatternContext mp -> typeMembershipPattern(mp, remaining);
            case BabbleParser.TypeOrDestructurePatternContext tp -> typeIsPattern(tp, remaining);
            case BabbleParser.BindingPatternContext bp -> typeBindingPattern(bp, remaining);
            default -> new PatternTyping(remaining, remaining);
        };
    }

    // ---------------------------------------------------------------- value & range patterns

    private PatternTyping typeValuePattern(BabbleParser.ValuePatternContext vp, Type remaining) {
        if (BlockScopes.isWildcardExpr(vp.expr)) {
            return new PatternTyping(remaining, Type.NOTHING);
        }
        SteppedRange extracted = extractRangePattern(vp.expr);
        if (extracted != null) {
            return typeRangeOrRangeEqPattern(vp, extracted, remaining);
        }
        Type valueExpected = expectedForValuePattern(remaining);
        Type patType = valueExpected != null ? checker.check(vp.expr, valueExpected) : checker.synth(vp.expr);
        if (patType == Type.ERROR || TypeChecker.isUnresolved(remaining) || remaining == Type.ERROR) {
            return new PatternTyping(remaining, remaining);
        }
        if (!overlapsValue(remaining, patType)) {
            checker.warn("Impossible pattern : " + remaining + " cannot match " + vp.getText(), vp);
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
        Type matched = patType == Type.NULL ? Type.NULL
                : (Type.isNumericScalar(patType) ? remaining : checker.algebra().narrowTarget(remaining, patType));
        return new PatternTyping(matched, remaining);
    }

    private record SteppedRange(BabbleParser.RangeExpressionContext range, BabbleParser.ExpressionContext step) {
    }

    private static SteppedRange extractRangePattern(BabbleParser.ExpressionContext expr) {
        BabbleParser.ExpressionContext cur = unwrapSingleParen(expr);
        if (cur instanceof BabbleParser.RangeExpressionContext r) {
            return new SteppedRange(r, null);
        }
        if (cur instanceof BabbleParser.CallContext call
                && call.expression() instanceof BabbleParser.SelectorContext sel
                && sel.expression() != null && sel.safe == null
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

    private static BabbleParser.ExpressionContext unwrapSingleParen(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext b && b.block().expression().size() == 1) {
            expr = b.block().expression(0);
        }
        return expr;
    }

    private PatternTyping typeRangeOrRangeEqPattern(BabbleParser.ValuePatternContext vp,
                                                    SteppedRange extracted, Type remaining) {
        if (remaining == Type.ERROR || remaining == Type.NOTHING) {
            checker.synth(vp.expr);
            return new PatternTyping(remaining, remaining);
        }
        Type nonNullRem = Type.withoutNull(remaining);
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
            checker.warn("Ambiguous range pattern : " + remaining + ". Use case in lo..hi or case == (lo..hi)", vp);
            checker.synth(vp.expr);
            return new PatternTyping(Type.ERROR, remaining);
        }
        if (hasRange) {
            // Pure range scrutinee: bare range pattern is range equality `v == (lo..hi)`.
            Type patType = checker.synth(vp.expr);
            if (!overlapsValue(remaining, patType)) {
                checker.warn("Impossible pattern : " + remaining + " cannot match " + vp.getText(), vp);
            }
            return new PatternTyping(nonNullRem, remaining);
        }
        // Ordered element scrutinee: zero-allocation range-membership pattern!
        BabbleParser.RangeExpressionContext r = extracted.range();
        Type expectedBound = expectedForValuePattern(nonNullRem);
        Type loType = expectedBound != null ? checker.check(r.left, expectedBound) : checker.synth(r.left);
        Type hiType = expectedBound != null ? checker.check(r.right, expectedBound) : checker.synth(r.right);
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
            checker.warn("Impossible pattern : " + remaining + " cannot match " + vp.getText(), vp);
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
        if (Type.isNumericScalar(nonNullRem) && Type.isNumericScalar(loType) && Type.isNumericScalar(hiType)) {
            int maxRank = Math.max(Type.numericRank(nonNullRem),
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
        rangePatterns.put(vp, new LoweringMetadata.RangePatternSource(
                r.left, r.right, extracted.step(), inclusive, elemKind, stringElem));
        return new PatternTyping(nonNullRem, remaining);
    }

    // ---------------------------------------------------------------- relational patterns

    private PatternTyping typeRelationalPattern(BabbleParser.RelationalPatternContext rp, Type remaining) {
        int opType = rp.op.getType();
        Type expectedRhs = expectedForValuePattern(remaining);
        Type rhsType = expectedRhs != null ? checker.check(rp.rhs, expectedRhs) : checker.synth(rp.rhs);
        if (rhsType == Type.ERROR || TypeChecker.isUnresolved(remaining) || remaining == Type.ERROR) {
            return new PatternTyping(remaining, remaining);
        }
        if (opType == BabbleLexer.EQ || opType == BabbleLexer.NEQ) {
            if (!overlapsValue(remaining, rhsType)) {
                checker.warn("Impossible pattern : " + remaining + " cannot match " + rp.getText(), rp);
                return new PatternTyping(Type.ERROR, remaining);
            }
            relationalKinds.put(rp, PrimitiveKind.of(remaining));
            return new PatternTyping(remaining, remaining);
        }
        Type nonNullRem = Type.withoutNull(remaining);
        if (nonNullRem == Type.NOTHING || Type.containsNull(rhsType) || !OperatorTypes.comparable(nonNullRem, rhsType)) {
            checker.warn("Impossible pattern : " + remaining + " cannot match " + rp.getText(), rp);
            return new PatternTyping(Type.ERROR, remaining);
        }
        relationalKinds.put(rp, PrimitiveKind.of(remaining));
        return new PatternTyping(nonNullRem, remaining);
    }

    // ---------------------------------------------------------------- membership (`in`) patterns

    private PatternTyping typeMembershipPattern(BabbleParser.MembershipPatternContext mp, Type remaining) {
        Type containerType = checker.synth(mp.container);
        if (containerType == Type.ERROR || TypeChecker.isUnresolved(containerType)) {
            return new PatternTyping(remaining, remaining);
        }
        Type unwrapped = TypeAlgebra.unwrapFrozen(containerType);
        if (Type.containsNull(unwrapped)) {
            checker.warn("Type error : expected range, collection, map, or array, got " + containerType, mp.container);
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
        } else if (checker.algebra().projectToJava(unwrapped, Collection.class) instanceof Type.JavaInstance col) {
            kind = LoweringTargets.MembershipKind.COLLECTION;
            elemType = col.typeArguments().isEmpty() ? Type.NULLABLE_ANY : col.typeArguments().get(0);
        } else if (checker.algebra().projectToJava(unwrapped, Map.class) instanceof Type.JavaInstance map) {
            kind = LoweringTargets.MembershipKind.MAP;
            elemType = map.typeArguments().isEmpty() ? Type.NULLABLE_ANY : map.typeArguments().get(0);
        } else if (unwrapped == Type.ANY) {
            kind = LoweringTargets.MembershipKind.DYNAMIC;
            elemType = Type.NULLABLE_ANY;
        } else {
            checker.warn("Type error : expected range, collection, map, or array, got " + containerType, mp.container);
            return new PatternTyping(Type.ERROR, remaining);
        }
        membershipTargets.put(mp, new LoweringTargets.MembershipTarget(kind, elemKind));
        if (!TypeChecker.isUnresolved(remaining) && remaining != Type.ERROR && !overlapsValue(remaining, elemType)) {
            checker.warn("Impossible pattern : " + remaining + " cannot match " + mp.getText(), mp);
            return new PatternTyping(Type.ERROR, remaining);
        }
        return new PatternTyping(remaining, remaining);
    }

    // ---------------------------------------------------------------- type & binding patterns

    private PatternTyping typeIsPattern(BabbleParser.TypeOrDestructurePatternContext tp, Type remaining) {
        ScopeNode scope = checker.scopeOf(tp);
        Type target = resolvePatternType(tp.patternType().nullableType(), tp.patternType(), scope, tp.patternArgs() != null, tp);
        if (target == Type.ERROR) {
            return new PatternTyping(Type.ERROR, remaining);
        }
        if (tp.patternArgs() != null) {
            // Destructuring support lands in P3; report clearly if invoked before P3.
            checker.warn("Destructuring pattern not yet supported : " + tp.getText(), tp);
            return new PatternTyping(Type.ERROR, remaining);
        }
        if (!TypeChecker.isUnresolved(remaining) && remaining != Type.ERROR
                && !checker.algebra().overlapsErased(remaining, target)) {
            checker.warn("Impossible pattern : " + remaining + " cannot match is " + target, tp);
            return new PatternTyping(Type.ERROR, remaining);
        }
        Type matched = !TypeChecker.isUnresolved(remaining) && remaining != Type.ERROR
                ? checker.algebra().narrowTarget(remaining, target) : target;
        Type subtracted = !TypeChecker.isUnresolved(remaining) && remaining != Type.ERROR
                ? checker.algebra().subtractType(remaining, target) : remaining;
        patternTargetTypes.put(tp, target);
        return new PatternTyping(matched, subtracted);
    }

    private PatternTyping typeBindingPattern(BabbleParser.BindingPatternContext bp, Type remaining) {
        if (bp.patternBindingType() == null) {
            checker.declarationTypes().put(bp, remaining);
            return new PatternTyping(remaining, remaining);
        }
        ScopeNode scope = checker.scopeOf(bp);
        BabbleParser.TypeContext typeCtx = bp.patternBindingType().type();
        Type target = resolveBindingType(typeCtx, scope, bp.patternArgs() != null, bp);
        if (target == Type.ERROR) {
            checker.declarationTypes().put(bp, Type.ERROR);
            return new PatternTyping(Type.ERROR, remaining);
        }
        if (bp.patternArgs() != null) {
            checker.warn("Destructuring pattern not yet supported : " + bp.getText(), bp);
            checker.declarationTypes().put(bp, Type.ERROR);
            return new PatternTyping(Type.ERROR, remaining);
        }
        if (!TypeChecker.isUnresolved(remaining) && remaining != Type.ERROR
                && !checker.algebra().overlapsErased(remaining, target)) {
            checker.warn("Impossible pattern : " + remaining + " cannot match def " + bp.name.getText() + ": " + target, bp);
            checker.declarationTypes().put(bp, Type.ERROR);
            return new PatternTyping(Type.ERROR, remaining);
        }
        Type matched = !TypeChecker.isUnresolved(remaining) && remaining != Type.ERROR
                ? checker.algebra().narrowTarget(remaining, target) : target;
        Type subtracted = !TypeChecker.isUnresolved(remaining) && remaining != Type.ERROR
                ? checker.algebra().subtractType(remaining, target) : remaining;
        checker.declarationTypes().put(bp, matched);
        patternTargetTypes.put(bp, target);
        return new PatternTyping(matched, subtracted);
    }

    private Type resolvePatternType(List<BabbleParser.NullableTypeContext> parts, ParserRuleContext at,
                                    ScopeNode scope, boolean hasArgs, ParserRuleContext owner) {
        BabbleParser.SimpleTypeContext singleSimple = null;
        if (parts.size() == 1
                && parts.get(0) instanceof BabbleParser.NonParenTypeContext nonParen
                && nonParen.nonParenNullableType() instanceof BabbleParser.SimpleTypeContext simple) {
            singleSimple = simple;
            if (!checkSingleSimpleType(simple, scope, hasArgs)) {
                return Type.ERROR;
            }
        }
        Type target = checker.annotator().groupAnnotation(parts, scope, true);
        if (!validateIsTargetType(target, at)) {
            return Type.ERROR;
        }
        recordBuiltinIfSimple(owner, singleSimple);
        return target;
    }

    private Type resolveBindingType(BabbleParser.TypeContext typeCtx, ScopeNode scope,
                                    boolean hasArgs, ParserRuleContext owner) {
        BabbleParser.SimpleTypeContext singleSimple = null;
        if (typeCtx.nullableType().size() == 1
                && typeCtx.nullableType(0) instanceof BabbleParser.NonParenTypeContext nonParen
                && nonParen.nonParenNullableType() instanceof BabbleParser.SimpleTypeContext simple) {
            singleSimple = simple;
            if (!checkSingleSimpleType(simple, scope, hasArgs)) {
                return Type.ERROR;
            }
        }
        Type target = checker.annotator().annotation(typeCtx, scope, true);
        if (!validateIsTargetType(target, typeCtx)) {
            return Type.ERROR;
        }
        recordBuiltinIfSimple(owner, singleSimple);
        return target;
    }

    private boolean checkSingleSimpleType(BabbleParser.SimpleTypeContext simple, ScopeNode scope, boolean hasArgs) {
        String name = simple.typeName().getText();
        if ("void".equals(name)) {
            checker.warn("Type error : cannot test 'is void'", simple);
            return false;
        }
        if (simple.frozen != null) {
            checker.warn("Cannot test 'frozen' with 'is'", simple);
            return false;
        }
        if (!hasArgs && simple.OF() != null && !"array".equals(name)
                && !checker.annotator().resolvesToArrayAlias(simple, scope)) {
            checker.warn("Cannot test erased type arguments with 'is' : use 'is " + name + "'", simple);
            return false;
        }
        return true;
    }

    private boolean validateIsTargetType(Type target, ParserRuleContext at) {
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
        return true;
    }

    private void recordBuiltinIfSimple(ParserRuleContext owner, BabbleParser.SimpleTypeContext simple) {
        if (simple != null && simple.typeName().coreTypeKeyword() != null
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
        if (remaining == Type.ERROR || patType == Type.ERROR || remaining == Type.NOTHING || patType == Type.NOTHING) {
            return true;
        }
        if (patType == Type.NULL) {
            return Type.containsNull(remaining) || remaining == Type.ANY || remaining instanceof Type.TypeVar;
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
    private LoweringTargets.PatternConst resolvePatternConst(BabbleParser.ExpressionContext expr, Type patType) {
        BabbleParser.ExpressionContext cur = unwrapSingleParen(expr);
        Object resolved = checker.resolvedLiterals().get(cur);
        if (resolved != null) {
            return new LoweringTargets.PatternConst(resolved, null, null, PrimitiveKind.of(patType));
        }
        if (cur instanceof BabbleParser.NullContext) {
            return new LoweringTargets.PatternConst(null, null, null, PrimitiveKind.NONE);
        }
        if (cur instanceof BabbleParser.BooleanContext b) {
            return new LoweringTargets.PatternConst(Boolean.valueOf(b.getText()), null, null, PrimitiveKind.BOOL);
        }
        if (cur instanceof BabbleParser.IntegerContext lit) {
            try {
                return new LoweringTargets.PatternConst(Integer.parseInt(lit.getText()), null, null, PrimitiveKind.INT);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (cur instanceof BabbleParser.LongLiteralContext lit) {
            try {
                String t = lit.getText();
                return new LoweringTargets.PatternConst(Long.parseLong(t.substring(0, t.length() - 1)), null, null, PrimitiveKind.LONG);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (cur instanceof BabbleParser.FloatLiteralContext lit) {
            try {
                String t = lit.getText();
                return new LoweringTargets.PatternConst(Float.parseFloat(t.substring(0, t.length() - 1)), null, null, PrimitiveKind.FLOAT);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (cur instanceof BabbleParser.DoubleContext lit) {
            try {
                return new LoweringTargets.PatternConst(Double.parseDouble(lit.getText()), null, null, PrimitiveKind.DOUBLE);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (cur instanceof BabbleParser.CharLiteralContext lit) {
            return new LoweringTargets.PatternConst(StringLiterals.decodeChar(lit.getText()), null, null, PrimitiveKind.CHAR);
        }
        if (cur instanceof BabbleParser.StringContext lit) {
            return new LoweringTargets.PatternConst(StringLiterals.decode(lit), null, null, PrimitiveKind.NONE);
        }
        if (cur instanceof BabbleParser.NegateContext neg) {
            Object negResolved = checker.resolvedLiterals().get(neg);
            if (negResolved != null) {
                return new LoweringTargets.PatternConst(negResolved, null, null, PrimitiveKind.of(patType));
            }
            LoweringTargets.PatternConst inner = resolvePatternConst(neg.operand, patType);
            if (inner != null && inner.value() instanceof Number n) {
                Object negated = switch (n) {
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
        if (cur instanceof BabbleParser.SelectorContext sel && sel.expression() != null && sel.safe == null) {
            Type recvType = checker.types().get(sel.expression());
            if (recvType instanceof Type.JavaClassValue jcv && jcv.type().isEnum()) {
                String constName = ScopeAnalysis.selectorName(sel);
                try {
                    Enum<?> enumVal = Enum.valueOf((Class<Enum>) jcv.type(), constName);
                    return new LoweringTargets.PatternConst(enumVal, jcv.type(), constName, PrimitiveKind.NONE);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
