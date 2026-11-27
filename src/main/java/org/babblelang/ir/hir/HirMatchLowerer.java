package org.babblelang.ir.hir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

/**
 * Pattern-matching ({@code match}/{@code case}) lowering for {@link HirBuilder} ({@code BIP038}).
 */
final class HirMatchLowerer {
    private final HirBuilder builder;

    HirMatchLowerer(HirBuilder builder) {
        this.builder = builder;
    }

    HirExpr lowerMatch(BabbleParser.MatchExpressionContext ctx) {
        HirExpr scrutinee = builder.lowerExpr(ctx.scrutinee);
        int ownerId =
                builder.currentFunction != null ? builder.ownerIdOf(builder.currentFunction) : 0;
        HirVar scrutineeVar =
                new HirVar(
                        builder.nextVarId++,
                        "$match",
                        Resolution.Variable.Kind.DEFINITION,
                        ownerId,
                        scrutinee.meta().kind(),
                        false,
                        true,
                        false,
                        true,
                        false,
                        false);
        List<HirExpr.MatchArm> arms = new ArrayList<>(ctx.caseClause().size());
        for (BabbleParser.CaseClauseContext cc : ctx.caseClause()) {
            HirPattern pattern = lowerPattern(cc.pattern());
            List<HirVar> boundVars = builder.hirVars(builder.resolution.declared(cc));
            HirExpr guard = cc.guard != null ? builder.lowerExpr(cc.guard) : null;
            HirExpr body = builder.lowerBlock(cc.armBlock, null);
            int bodyWiden = builder.scopes.lowering().numericWideningRank(cc.armBlock);
            arms.add(
                    new HirExpr.MatchArm(
                            pattern, boundVars, guard, body, bodyWiden, cc.getStart().getLine()));
        }
        HirExpr.MatchElse elseArm = null;
        if (ctx.elseClause() != null) {
            BabbleParser.ElseClauseContext ec = ctx.elseClause();
            HirVar elseVar =
                    ec.name != null ? builder.hirVar(builder.resolution.variable(ec)) : null;
            HirExpr elseBody = builder.lowerBlock(ec.elseBlock, null);
            int elseWiden = builder.scopes.lowering().numericWideningRank(ec.elseBlock);
            elseArm = new HirExpr.MatchElse(elseVar, elseBody, elseWiden, ec.getStart().getLine());
        }
        boolean exhaustive = builder.scopes.lowering().isMatchExhaustive(ctx);
        return new HirExpr.Match(
                scrutinee, scrutineeVar, arms, elseArm, exhaustive, builder.metaOf(ctx));
    }

    private HirPattern lowerPattern(BabbleParser.PatternContext pat) {
        List<BabbleParser.AtomicPatternContext> atoms = pat.atomicPattern();
        if (atoms.size() == 1) {
            return lowerAtomicPattern(atoms.get(0));
        }
        List<HirPattern> alts = new ArrayList<>(atoms.size());
        for (BabbleParser.AtomicPatternContext atom : atoms) {
            alts.add(lowerAtomicPattern(atom));
        }
        return new HirPattern.Or(alts, pat.getStart().getLine());
    }

    private HirPattern lowerAtomicPattern(BabbleParser.AtomicPatternContext atom) {
        int line = atom.getStart().getLine();
        return switch (atom) {
            case BabbleParser.ValuePatternContext vp -> {
                if (vp.expr instanceof BabbleParser.SelectorContext sel
                        && sel.expression() == null
                        && "_".equals(sel.ID().getText())) {
                    yield new HirPattern.Wildcard(line);
                }
                var rangeSrc = builder.scopes.lowering().rangePatternSource(vp);
                if (rangeSrc != null) {
                    HirExpr lo = builder.lowerExpr(rangeSrc.lo());
                    HirExpr hi = builder.lowerExpr(rangeSrc.hi());
                    HirExpr step =
                            rangeSrc.step() != null ? builder.lowerExpr(rangeSrc.step()) : null;
                    yield new HirPattern.RangeTest(
                            lo,
                            hi,
                            step,
                            rangeSrc.inclusive(),
                            rangeSrc.elemKind(),
                            rangeSrc.stringElement(),
                            line);
                }
                LoweringTargets.PatternConst pc = builder.scopes.lowering().patternConst(vp);
                if (pc != null) {
                    yield new HirPattern.Const(
                            pc.value(), pc.enumClass(), pc.enumConstantName(), pc.kind(), line);
                }
                yield new HirPattern.ValueExpr(builder.lowerExpr(vp.expr), line);
            }
            case BabbleParser.RelationalPatternContext rp -> {
                HirExpr.BinaryOp op =
                        switch (rp.op.getType()) {
                            case BabbleLexer.LT -> HirExpr.BinaryOp.LT;
                            case BabbleLexer.LTE -> HirExpr.BinaryOp.LTE;
                            case BabbleLexer.GT -> HirExpr.BinaryOp.GT;
                            case BabbleLexer.GTE -> HirExpr.BinaryOp.GTE;
                            case BabbleLexer.EQ -> HirExpr.BinaryOp.EQ;
                            case BabbleLexer.NEQ -> HirExpr.BinaryOp.NEQ;
                            default ->
                                    throw new IllegalStateException(
                                            "Unexpected relational op : " + rp.op.getText());
                        };
                yield new HirPattern.Relational(
                        op,
                        builder.lowerExpr(rp.rhs),
                        builder.scopes.lowering().relationalScrutineeKind(rp),
                        line);
            }
            case BabbleParser.MembershipPatternContext mp -> {
                LoweringTargets.MembershipTarget mt =
                        builder.scopes.lowering().membershipTarget(mp);
                LoweringTargets.MembershipKind kind =
                        mt != null ? mt.kind() : LoweringTargets.MembershipKind.DYNAMIC;
                PrimitiveKind elemKind = mt != null ? mt.elemKind() : PrimitiveKind.NONE;
                yield new HirPattern.Membership(
                        builder.lowerExpr(mp.container), kind, elemKind, line);
            }
            case BabbleParser.TypeOrDestructurePatternContext tp -> {
                if (tp.patternArgs() != null) {
                    yield lowerDestructurePattern(tp, tp.patternArgs(), line);
                }
                yield lowerTypeTest(builder.scopes.lowering().patternTypeTarget(tp), line);
            }
            case BabbleParser.BindingPatternContext bp -> {
                HirVar var = builder.hirVar(builder.resolution.variable(bp));
                HirPattern inner = null;
                if (bp.patternArgs() != null) {
                    inner = lowerDestructurePattern(bp, bp.patternArgs(), line);
                } else if (bp.patternBindingType() != null) {
                    inner = lowerTypeTest(builder.scopes.lowering().patternTypeTarget(bp), line);
                }
                yield new HirPattern.Bind(var, inner, line);
            }
            default -> new HirPattern.Wildcard(line);
        };
    }

    private HirPattern.TypeTest lowerTypeTest(LoweringTargets.PatternTypeTarget target, int line) {
        return new HirPattern.TypeTest(
                target != null ? target.builtin() : null,
                target != null ? target.target() : null,
                target != null ? target.intersectionTargets() : List.of(),
                target != null ? target.descriptor() : null,
                line);
    }

    private HirPattern lowerDestructurePattern(
            ParserRuleContext owner, BabbleParser.PatternArgsContext args, int line) {
        LoweringTargets.RecordDestructureTarget rd =
                builder.scopes.lowering().recordDestructureTarget(owner);
        if (rd != null) {
            List<HirPattern> subPats = new ArrayList<>(args.patternArg().size());
            for (BabbleParser.PatternArgContext arg : args.patternArg()) {
                subPats.add(lowerPattern(arg.pattern()));
            }
            int classId = -1;
            String jvmName = null;
            if (!rd.isJavaRecord() && rd.babbleTypeId() != null) {
                classId = builder.localClassIdsByShapeId.getOrDefault(rd.babbleTypeId(), -1);
                jvmName =
                        builder.localClassJvmNamesById.getOrDefault(
                                rd.babbleTypeId(), builder.allClassJvmNames.get(rd.babbleTypeId()));
            }
            return new HirPattern.RecordDestructure(
                    lowerTypeTest(rd.typeTest(), line),
                    rd.isJavaRecord(),
                    classId,
                    rd.babbleTypeId(),
                    jvmName,
                    rd.javaRecordClass(),
                    Arrays.asList(rd.fieldNames()),
                    rd.babbleMemberIndices(),
                    Arrays.asList(rd.fieldKinds()),
                    Arrays.asList(rd.javaAccessors()),
                    subPats,
                    line);
        }
        LoweringTargets.SequenceDestructureTarget sd =
                builder.scopes.lowering().sequenceDestructureTarget(owner);
        if (sd != null) {
            List<HirPattern> elemPats = new ArrayList<>(args.patternArg().size());
            for (BabbleParser.PatternArgContext arg : args.patternArg()) {
                elemPats.add(lowerPattern(arg.pattern()));
            }
            HirVar restVar =
                    (args.restPattern() != null && args.restPattern().name != null)
                            ? builder.hirVar(builder.resolution.variable(args.restPattern()))
                            : null;
            HirPattern.TypeTest tt =
                    sd.typeTest() != null ? lowerTypeTest(sd.typeTest(), line) : null;
            return new HirPattern.SequenceDestructure(
                    tt,
                    sd.isArray(),
                    sd.arrayClass(),
                    sd.elemKind(),
                    elemPats,
                    sd.hasRest(),
                    restVar,
                    line);
        }
        return lowerTypeTest(builder.scopes.lowering().patternTypeTarget(owner), line);
    }
}
