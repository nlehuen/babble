package org.babblelang.opt.hir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirPattern;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;
import org.babblelang.runtime.ClassShape;

/**
 * Sub-pass 1 of {@link ScalarReplacePass}: projection beta-reduction ({@code
 * ThisFieldGet(RecordNew(C, args), p) -> args[p]}) and {@code BIP038} static {@code match}
 * destructuring reduction over {@code RecordNew} values.
 */
final class RecordBetaReducer {
    private RecordBetaReducer() {}

    record StaticMatchResult(HirExpr.MatchArm arm, boolean isElse) {}

    static HirModule betaReduceModule(
            HirModule module,
            Map<String, RecordLayout> localLayouts,
            Set<String> initBindings,
            int[] nextVarId,
            int[] rewrites) {
        boolean[] changed = {false};
        List<HirExpr> newTop =
                HirRewriter.rewriteList(
                        module.topLevelStatements(),
                        s ->
                                betaReduceExpr(
                                        s,
                                        -1,
                                        module,
                                        localLayouts,
                                        initBindings,
                                        nextVarId,
                                        rewrites,
                                        changed));
        Map<Integer, HirFunction> newFns = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirFunction> e : module.functions().entrySet()) {
            HirFunction fn = e.getValue();
            int ownerId = fn.namespaceScope() ? -1 : fn.id();
            List<HirExpr> nb =
                    HirRewriter.rewriteList(
                            fn.body(),
                            s ->
                                    betaReduceExpr(
                                            s,
                                            ownerId,
                                            module,
                                            localLayouts,
                                            initBindings,
                                            nextVarId,
                                            rewrites,
                                            changed));
            if (nb != fn.body()) {
                fn = fn.withBody(nb);
            }
            newFns.put(e.getKey(), fn);
        }
        if (!changed[0]) {
            return module;
        }
        return module.withUpdates(
                newTop,
                newFns,
                module.classes(),
                module.interfaces(),
                nextVarId[0],
                module.nextOwnerId());
    }

    private static HirExpr betaReduceExpr(
            HirExpr expr,
            int ownerFnId,
            HirModule module,
            Map<String, RecordLayout> localLayouts,
            Set<String> initBindings,
            int[] nextVarId,
            int[] rewrites,
            boolean[] changed) {
        HirExpr cur =
                HirRewriter.rewriteChildren(
                        expr,
                        c ->
                                betaReduceExpr(
                                        c,
                                        ownerFnId,
                                        module,
                                        localLayouts,
                                        initBindings,
                                        nextVarId,
                                        rewrites,
                                        changed));
        if (cur instanceof HirExpr.Match m && m.meta().checkedType() == null) {
            HirExpr reducedMatch =
                    tryBetaReduceMatch(m, ownerFnId, module, localLayouts, initBindings);
            if (reducedMatch != null) {
                rewrites[0]++;
                changed[0] = true;
                return reducedMatch;
            }
            return cur;
        }
        if (!(cur instanceof HirExpr.ThisFieldGet g) || g.meta().checkedType() != null) {
            return cur;
        }
        HirExpr recv = RecordLayout.unwrapOwnership(g.receiver());
        if (recv instanceof HirExpr.RecordNew r) {
            HirExpr reduced =
                    reduceRecordProjection(
                            g, r, ownerFnId, module, localLayouts, initBindings, nextVarId);
            if (reduced != null) {
                rewrites[0]++;
                changed[0] = true;
                return reduced;
            }
        } else if (recv instanceof HirExpr.Block b
                && !b.opensNamespaceScope()
                && !b.statements().isEmpty()
                && b.innerWideningRank() < 0
                && b.meta().wideningRank() < 0
                && b.meta().checkedType() == null) {
            int last = b.statements().size() - 1;
            HirExpr tail = RecordLayout.unwrapOwnership(b.statements().get(last));
            if (tail instanceof HirExpr.RecordNew r) {
                HirExpr reduced =
                        reduceRecordProjection(
                                g, r, ownerFnId, module, localLayouts, initBindings, nextVarId);
                if (reduced != null) {
                    List<HirExpr> stmts = new ArrayList<>(b.statements());
                    stmts.set(last, reduced);
                    rewrites[0]++;
                    changed[0] = true;
                    return new HirExpr.Block(
                            stmts, false, new String[0], b.declaredVars(), -1, g.meta());
                }
            }
        }
        return cur;
    }

    private static HirExpr reduceRecordProjection(
            HirExpr.ThisFieldGet g,
            HirExpr.RecordNew r,
            int ownerFnId,
            HirModule module,
            Map<String, RecordLayout> localLayouts,
            Set<String> initBindings,
            int[] nextVarId) {
        RecordLayout layout = RecordLayout.layoutOf(r, localLayouts, module);
        if (layout == null) {
            return null;
        }
        int p = RecordLayout.matchingParamIndex(g, layout);
        if (p < 0) {
            return null;
        }
        int[] argMap = RecordLayout.resolveArgMapping(r);
        if (argMap == null) {
            return null;
        }
        int chosenIdx = argMap[p];
        HirExpr chosenArg = r.args().get(chosenIdx);
        PrimitiveKind fieldKind = layout.paramKinds()[p];

        boolean classRefPure =
                RecordLayout.isPureClassRef(
                        r.classRef(), r.classRefIsInstance(), module, initBindings);
        boolean allSiblingsPure = true;
        for (int i = 0; i < r.args().size(); i++) {
            if (i != chosenIdx && !HirPurity.isPureNothrowTerminating(r.args().get(i), module)) {
                allSiblingsPure = false;
                break;
            }
        }

        if (classRefPure && allSiblingsPure) {
            return adaptProjectedArg(chosenArg, fieldKind, g.meta());
        }
        // Preserve left-to-right side effects only inside frame-scoped functions where we can bind
        // a temp local.
        if (ownerFnId < 0) {
            return null;
        }
        List<HirExpr> stmts = new ArrayList<>();
        List<HirVar> temps = new ArrayList<>();
        if (!classRefPure) {
            stmts.add(r.classRef());
        }
        for (int i = 0; i < chosenIdx; i++) {
            if (!HirPurity.isPureNothrowTerminating(r.args().get(i), module)) {
                stmts.add(r.args().get(i));
            }
        }
        int tmpId = nextVarId[0]++;
        HirVar tmp =
                new HirVar(
                        tmpId,
                        "$sr_p" + tmpId,
                        Resolution.Variable.Kind.DEFINITION,
                        ownerFnId,
                        fieldKind,
                        false,
                        true,
                        false,
                        true);
        temps.add(tmp);
        ExprMeta defMeta =
                new ExprMeta(
                        chosenArg.meta().line(),
                        fieldKind,
                        chosenArg.meta().wideningRank(),
                        null,
                        EffectSet.EMPTY,
                        g.meta().deeplyImmutable(),
                        g.meta().stringType());
        stmts.add(
                new HirExpr.Def(
                        tmp.name(),
                        new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1),
                        tmp,
                        chosenArg,
                        defMeta));
        for (int i = chosenIdx + 1; i < r.args().size(); i++) {
            if (!HirPurity.isPureNothrowTerminating(r.args().get(i), module)) {
                stmts.add(r.args().get(i));
            }
        }
        ExprMeta readMeta =
                new ExprMeta(
                        g.meta().line(),
                        fieldKind,
                        g.meta().wideningRank(),
                        null,
                        EffectSet.EMPTY,
                        g.meta().deeplyImmutable(),
                        g.meta().stringType());
        stmts.add(new HirExpr.VarRead(tmp, readMeta));
        return new HirExpr.Block(stmts, false, new String[0], temps, -1, g.meta());
    }

    private static HirExpr adaptProjectedArg(
            HirExpr arg, PrimitiveKind fieldKind, ExprMeta getMeta) {
        int fieldRank = fieldKind.numericRank();
        boolean kindMatch =
                arg.meta().kind() == fieldKind
                        || (fieldRank >= 1 && arg.meta().wideningRank() == fieldRank);
        if (getMeta.wideningRank() < 0 && kindMatch) {
            return arg;
        }
        int targetRank =
                getMeta.wideningRank() >= 0
                        ? getMeta.wideningRank()
                        : (arg.meta().kind() != fieldKind && fieldRank >= 1 ? fieldRank : -1);
        ExprMeta wrapMeta =
                new ExprMeta(
                        getMeta.line(),
                        fieldKind,
                        targetRank,
                        null,
                        arg.meta().effects(),
                        getMeta.deeplyImmutable(),
                        getMeta.stringType(),
                        arg.meta().frozen(),
                        arg.meta().unique());
        return new HirExpr.Block(List.of(arg), false, new String[0], List.of(), -1, wrapMeta);
    }

    // ---------------------------------------------------------------- BIP038 match RecordNew SROA

    static StaticMatchResult findStaticMatchArm(
            HirExpr.Match m, RecordLayout layout, HirModule module) {
        for (HirExpr.MatchArm arm : m.arms()) {
            if (arm.guard() == null
                    && patternDefinitelyMatchesLayout(arm.pattern(), layout, arm.boundVars())) {
                return new StaticMatchResult(arm, false);
            }
            if (patternDefinitelyRejectsLayout(arm.pattern(), layout)
                    && HirPurity.isPatternPureNothrowTerminating(arm.pattern(), module)) {
                continue;
            }
            return null;
        }
        if (m.elseArm() != null && m.elseArm().var() == null) {
            return new StaticMatchResult(null, true);
        }
        return null;
    }

    private static boolean patternDefinitelyMatchesLayout(
            HirPattern pat, RecordLayout layout, List<HirVar> boundVars) {
        if (pat instanceof HirPattern.RecordDestructure rd) {
            if (rd.isJavaRecord() || !layout.classTypeId().equals(rd.babbleTypeId())) {
                return false;
            }
            int[] memberIndices = rd.babbleMemberIndices();
            if (memberIndices.length != rd.fieldPatterns().size()) {
                return false;
            }
            int boundCount = 0;
            for (int k = 0; k < rd.fieldPatterns().size(); k++) {
                HirPattern fp = rd.fieldPatterns().get(k);
                int p = layout.paramIndexForMember(memberIndices[k]);
                if (p < 0) {
                    return false;
                }
                if (fp instanceof HirPattern.Wildcard) {
                    continue;
                }
                if (fp instanceof HirPattern.Bind b
                        && b.inner() == null
                        && !b.var().isCaptured()
                        && b.var().primitiveKind() == layout.paramKinds()[p]) {
                    boundCount++;
                    continue;
                }
                return false;
            }
            return boundCount == boundVars.size();
        }
        if (pat instanceof HirPattern.TypeTest tt && boundVars.isEmpty()) {
            return tt.target() != null && layout.classTypeId().equals(tt.target().typeId());
        }
        return false;
    }

    private static boolean patternDefinitelyRejectsLayout(HirPattern pat, RecordLayout layout) {
        return switch (pat) {
            case HirPattern.Const c -> c.isNull() || c.isEnum() || c.value() != null;
            case HirPattern.TypeTest tt -> {
                if (tt.builtin() != null) {
                    yield true;
                }
                if (tt.target() != null
                        && tt.target().kind() != ClassShape.Kind.INTERFACE
                        && !layout.classTypeId().equals(tt.target().typeId())) {
                    yield true;
                }
                yield false;
            }
            case HirPattern.RecordDestructure rd ->
                    rd.isJavaRecord() || !layout.classTypeId().equals(rd.babbleTypeId());
            case HirPattern.SequenceDestructure ignored -> true;
            case HirPattern.Bind b ->
                    b.inner() != null && patternDefinitelyRejectsLayout(b.inner(), layout);
            case HirPattern.Or o -> {
                for (HirPattern alt : o.alternatives()) {
                    if (!patternDefinitelyRejectsLayout(alt, layout)) {
                        yield false;
                    }
                }
                yield true;
            }
            default -> false;
        };
    }

    private static HirExpr tryBetaReduceMatch(
            HirExpr.Match m,
            int ownerFnId,
            HirModule module,
            Map<String, RecordLayout> localLayouts,
            Set<String> initBindings) {
        HirExpr scrut = RecordLayout.unwrapOwnership(m.scrutinee());
        HirExpr.Block prefixBlock = null;
        HirExpr.RecordNew r = null;
        if (scrut instanceof HirExpr.RecordNew rn) {
            r = rn;
        } else if (scrut instanceof HirExpr.Block b
                && !b.opensNamespaceScope()
                && !b.statements().isEmpty()
                && b.innerWideningRank() < 0
                && b.meta().wideningRank() < 0
                && b.meta().checkedType() == null) {
            HirExpr tail =
                    RecordLayout.unwrapOwnership(b.statements().get(b.statements().size() - 1));
            if (tail instanceof HirExpr.RecordNew rn) {
                prefixBlock = b;
                r = rn;
            }
        }
        if (r == null) {
            return null;
        }
        RecordLayout layout = RecordLayout.layoutOf(r, localLayouts, module);
        int[] argMap = layout != null ? RecordLayout.resolveArgMapping(r) : null;
        if (layout == null || argMap == null) {
            return null;
        }
        StaticMatchResult res = findStaticMatchArm(m, layout, module);
        if (res == null) {
            return null;
        }
        boolean classRefPure =
                RecordLayout.isPureClassRef(
                        r.classRef(), r.classRefIsInstance(), module, initBindings);
        if (!classRefPure && ownerFnId < 0) {
            return null;
        }

        HirVar[] boundByArgIdx = new HirVar[r.args().size()];
        PrimitiveKind[] kindByArgIdx = new PrimitiveKind[r.args().size()];
        List<HirVar> declared = new ArrayList<>();
        if (!res.isElse() && res.arm().pattern() instanceof HirPattern.RecordDestructure rd) {
            int[] memberIndices = rd.babbleMemberIndices();
            for (int k = 0; k < rd.fieldPatterns().size(); k++) {
                if (rd.fieldPatterns().get(k) instanceof HirPattern.Bind b) {
                    int p = layout.paramIndexForMember(memberIndices[k]);
                    int argIdx = argMap[p];
                    if (boundByArgIdx[argIdx] != null) {
                        return null;
                    }
                    boundByArgIdx[argIdx] = b.var();
                    kindByArgIdx[argIdx] = layout.paramKinds()[p];
                }
            }
            declared.addAll(res.arm().boundVars());
        }

        List<HirExpr> stmts = new ArrayList<>();
        if (!classRefPure) {
            stmts.add(r.classRef());
        }
        for (int aIdx = 0; aIdx < r.args().size(); aIdx++) {
            HirExpr arg = r.args().get(aIdx);
            HirVar bv = boundByArgIdx[aIdx];
            if (bv != null) {
                PrimitiveKind fk = kindByArgIdx[aIdx];
                ExprMeta defMeta =
                        new ExprMeta(
                                arg.meta().line(),
                                fk,
                                arg.meta().kind() != fk && fk.numericRank() >= 1
                                        ? fk.numericRank()
                                        : arg.meta().wideningRank(),
                                null,
                                arg.meta().effects(),
                                fk.isPrimitive(),
                                arg.meta().stringType());
                stmts.add(
                        new HirExpr.Def(
                                bv.name(),
                                new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1),
                                bv,
                                arg,
                                defMeta));
            } else if (!HirPurity.isPureNothrowTerminating(arg, module)) {
                stmts.add(arg);
            }
        }
        HirExpr body = res.isElse() ? m.elseArm().body() : res.arm().body();
        int bodyWiden =
                res.isElse() ? m.elseArm().bodyWideningRank() : res.arm().bodyWideningRank();
        stmts.add(body);

        HirExpr reduced =
                new HirExpr.Block(stmts, false, new String[0], declared, bodyWiden, m.meta());
        if (prefixBlock != null) {
            List<HirExpr> combined = new ArrayList<>(prefixBlock.statements());
            combined.set(combined.size() - 1, reduced);
            return new HirExpr.Block(
                    combined, false, new String[0], prefixBlock.declaredVars(), -1, m.meta());
        }
        return reduced;
    }
}
