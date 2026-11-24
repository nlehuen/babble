package org.babblelang.opt.hir;

import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code O10} — Functional-but-in-place (FBIP) reuse optimization backed by {@code BIP007-I2}
 * static uniqueness analysis ({@code BIP014-P4}).
 *
 * <p>Rewrites two safe allocation-avoiding patterns:
 * <ol>
 *   <li><b>Collection reuse</b>: {@code list(from: move(xs))}, {@code set(from: move(s))}, and
 *       {@code map(from: move(m))} where {@code move(...)} is proven statically unique and
 *       non-frozen by {@code EffectChecker} lower to {@code list$reuse}/{@code set$reuse}/
 *       {@code map$reuse} ({@code CoreCollections.*ReuseOrCopy}).</li>
 *   <li><b>Mutable {@code record} in-place reuse</b>: inside a frame-scoped function taking a
 *       {@code unique} parameter {@code p: R} of a non-deeply-immutable local {@code fastRecord}
 *       {@code R} (so {@code EffectChecker} strictly forbids aliased callers), when {@code p}
 *       never escapes or mutates and a same-type {@link HirExpr.RecordNew} appears in function
 *       tail position, rewrites that {@code RecordNew} to {@link HirExpr.RecordReuse} mutating
 *       {@code p}'s fields in place after evaluating all field arguments.</li>
 * </ol>
 */
public final class FbipReusePass implements HirPass {
    @Override
    public String name() {
        return "FbipReuse";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] rewrites = {0};
        Set<String> initBindings = HirPurity.findDefinitelyInitializedTopLevelBindings(module);

        List<HirExpr> newTop = HirRewriter.rewriteList(module.topLevelStatements(),
                s -> rewriteCollections(s, rewrites));

        Map<Integer, HirFunction> newFns = new LinkedHashMap<>();
        boolean fnChanged = false;
        for (Map.Entry<Integer, HirFunction> e : module.functions().entrySet()) {
            HirFunction fn = e.getValue();
            List<HirExpr> body = HirRewriter.rewriteList(fn.body(), s -> rewriteCollections(s, rewrites));
            if (!fn.namespaceScope()) {
                body = rewriteTailRecordReuse(fn, body, module, initBindings, rewrites);
            }
            if (body != fn.body()) {
                fnChanged = true;
                fn = fn.withBody(body);
            }
            newFns.put(e.getKey(), fn);
        }

        if (rewrites[0] == 0 && newTop == module.topLevelStatements() && !fnChanged) {
            return Result.unchanged(module);
        }
        return new Result(module.withUpdates(newTop, newFns, module.classes(), module.interfaces(),
                module.nextVarId(), module.nextOwnerId()), rewrites[0]);
    }

    // ---------------------------------------------------------------- Pattern A: collection FBIP reuse

    private HirExpr rewriteCollections(HirExpr expr, int[] rewrites) {
        HirExpr cur = HirRewriter.rewriteChildren(expr, c -> rewriteCollections(c, rewrites));
        if (!(cur instanceof HirExpr.CoreCollectionCall c)) {
            return cur;
        }
        String kind = c.collectionKind();
        if (!"list".equals(kind) && !"set".equals(kind) && !"map".equals(kind)) {
            return cur;
        }
        if (c.args().size() != 1 || !"from".equals(c.args().get(0).name())) {
            return cur;
        }
        HirExpr fromVal = c.args().get(0).value();
        if (!(fromVal instanceof HirExpr.Move m)) {
            return cur;
        }
        if (m.meta().frozen() || m.operand().meta().frozen() || m.meta().deeplyImmutable()) {
            return cur;
        }
        // EffectChecker guarantees every Move targets a statically unique, unaliased local variable
        // that is marked MOVED immediately after this expression.
        rewrites[0]++;
        return new HirExpr.CoreCollectionCall(kind + "$reuse", c.args(), c.meta());
    }

    // ---------------------------------------------------------------- Pattern B: unique record in-place reuse

    private List<HirExpr> rewriteTailRecordReuse(HirFunction fn, List<HirExpr> body, HirModule module,
                                                 Set<String> initBindings, int[] rewrites) {
        if (body.isEmpty()) {
            return body;
        }
        // Collect unique parameters whose type is a local fastRecord.
        Map<String, HirParam> uniqueByTypeId = new HashMap<>();
        Set<Integer> uniqueParamVarIds = new HashSet<>();
        for (HirParam p : fn.params()) {
            if (!p.isUnique() || p.recordTypeId() == null || p.var() == null || p.var().cell()) {
                continue;
            }
            HirClass cls = findLocalFastRecord(module, p.recordTypeId());
            if (cls == null) {
                continue;
            }
            // If two params share the same record type, keep the first eligible one.
            if (!uniqueByTypeId.containsKey(p.recordTypeId())) {
                uniqueByTypeId.put(p.recordTypeId(), p);
                uniqueParamVarIds.add(p.var().id());
            }
        }
        if (uniqueByTypeId.isEmpty()) {
            return body;
        }

        // Verify which unique params never escape or get reassigned anywhere in body:
        // every read of p.var() must be a valid ThisFieldGet (so p itself is never stored into a field,
        // passed as an argument, returned directly, or placed inside the reused record creating a cycle).
        Set<Integer> disqualified = new HashSet<>();
        for (HirExpr s : body) {
            checkUniqueParamEscapes(s, uniqueParamVarIds, disqualified);
        }
        uniqueByTypeId.values().removeIf(p -> disqualified.contains(p.var().id()));
        if (uniqueByTypeId.isEmpty()) {
            return body;
        }

        int last = body.size() - 1;
        HirExpr oldTail = body.get(last);
        HirExpr newTail = rewriteTailExpr(oldTail, uniqueByTypeId, module, initBindings, rewrites);
        if (newTail == oldTail) {
            return body;
        }
        List<HirExpr> out = new java.util.ArrayList<>(body);
        out.set(last, newTail);
        return out;
    }

    private static HirClass findLocalFastRecord(HirModule module, String typeId) {
        for (HirClass cls : module.classes().values()) {
            if (cls.fastRecord() && cls.shape().id().equals(typeId)) {
                return cls;
            }
        }
        return null;
    }

    private static void checkUniqueParamEscapes(HirExpr expr, Set<Integer> uniqueVarIds, Set<Integer> disqualified) {
        if (expr == null) return;
        if (expr instanceof HirExpr.ThisFieldGet g && g.receiver() instanceof HirExpr.VarRead vr
                && uniqueVarIds.contains(vr.var().id())) {
            return;
        }
        if (expr instanceof HirExpr.VarRead vr) {
            if (uniqueVarIds.contains(vr.var().id())) {
                disqualified.add(vr.var().id());
            }
            return;
        }
        if (expr instanceof HirExpr.VarAssign a) {
            if (uniqueVarIds.contains(a.var().id())) {
                disqualified.add(a.var().id());
            }
            checkUniqueParamEscapes(a.value(), uniqueVarIds, disqualified);
            return;
        }
        HirRewriter.rewriteChildren(expr, c -> {
            checkUniqueParamEscapes(c, uniqueVarIds, disqualified);
            return c;
        });
    }

    private HirExpr rewriteTailExpr(HirExpr expr, Map<String, HirParam> uniqueByTypeId,
                                    HirModule module, Set<String> initBindings, int[] rewrites) {
        if (expr == null) return null;
        if (expr instanceof HirExpr.RecordNew r) {
            return tryConvertToReuse(r, uniqueByTypeId, module, initBindings, rewrites);
        }
        if (expr instanceof HirExpr.Block b && !b.opensNamespaceScope() && !b.statements().isEmpty()) {
            int last = b.statements().size() - 1;
            HirExpr oldLast = b.statements().get(last);
            HirExpr newLast = rewriteTailExpr(oldLast, uniqueByTypeId, module, initBindings, rewrites);
            if (newLast != oldLast) {
                List<HirExpr> ns = new java.util.ArrayList<>(b.statements());
                ns.set(last, newLast);
                return new HirExpr.Block(ns, b.opensNamespaceScope(), b.declaredNames(), b.declaredVars(),
                        b.innerWideningRank(), b.meta());
            }
            return b;
        }
        if (expr instanceof HirExpr.If i) {
            HirExpr nt = rewriteTailExpr(i.thenBranch(), uniqueByTypeId, module, initBindings, rewrites);
            HirExpr ne = i.elseBranch() != null
                    ? rewriteTailExpr(i.elseBranch(), uniqueByTypeId, module, initBindings, rewrites)
                    : null;
            if (nt != i.thenBranch() || ne != i.elseBranch()) {
                return new HirExpr.If(i.cond(), nt, ne, i.meta());
            }
            return i;
        }
        if (expr instanceof HirExpr.Return ret) {
            HirExpr nv = rewriteTailExpr(ret.value(), uniqueByTypeId, module, initBindings, rewrites);
            if (nv != ret.value()) {
                return new HirExpr.Return(nv, ret.meta());
            }
            return ret;
        }
        return expr;
    }

    private HirExpr tryConvertToReuse(HirExpr.RecordNew r, Map<String, HirParam> uniqueByTypeId,
                                      HirModule module, Set<String> initBindings, int[] rewrites) {
        // Soundness gate: EffectChecker enforces strict unaliased uniqueness on unique params ONLY
        // when the record type is NOT deeply immutable (i.e. contains at least one mutable field).
        if (r.meta().deeplyImmutable() || r.meta().frozen() || r.classId() < 0) {
            return r;
        }
        HirParam targetParam = uniqueByTypeId.get(r.classTypeId());
        if (targetParam == null) {
            return r;
        }
        HirClass cls = module.clazz(r.classId());
        if (cls == null || !cls.fastRecord() || r.args().size() != cls.params().size()) {
            return r;
        }
        if (r.binding() != null && (r.binding().reordered() || r.binding().restParam() >= 0 || r.binding().missingParamBits() != 0)) {
            return r;
        }
        boolean classRefPure = (r.classRef() instanceof HirExpr.NameRead nr && initBindings.contains(nr.name()))
                || r.classRef() instanceof HirExpr.ClassExpr
                || HirPurity.isPureNothrowTerminating(r.classRef(), module);
        if (!classRefPure) {
            return r;
        }
        rewrites[0]++;
        HirExpr targetRead = new HirExpr.VarRead(targetParam.var(), r.meta());
        return new HirExpr.RecordReuse(targetRead, r.classId(), r.classTypeId(), r.classJvmName(),
                r.paramKinds(), r.args(), r.meta());
    }
}
