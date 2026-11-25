package org.babblelang.opt.mir;

import org.babblelang.ir.mir.MirBlock;
import org.babblelang.ir.mir.MirCfg;
import org.babblelang.ir.mir.MirEffect;
import org.babblelang.ir.mir.MirFunction;
import org.babblelang.ir.mir.MirInst;
import org.babblelang.ir.mir.MirModule;
import org.babblelang.ir.mir.MirTerminator;
import org.babblelang.ir.mir.MirVal;
import org.babblelang.opt.PassContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Alias-aware Redundant Load Elimination and Store-to-Load Forwarding ({@code O7}) over SSA
 * {@link MirModule} ({@code BIP014-I3} §6.4).
 *
 * <p>Uses {@link MirEffect.AliasClass} ({@code FROZEN}, {@code UNIQUE_PARAM(k)}, {@code SHARED})
 * to forward {@code StoreField}/{@code StoreJavaField} values directly to dominating loads and
 * eliminate redundant {@code LoadField}/{@code LoadJavaField} instructions across disjoint writes
 * (e.g., writes to a {@code unique} parameter never invalidate reads from a different {@code unique}
 * parameter or {@code frozen} records).
 */
public final class AliasRlePass implements MirPass {
    @Override
    public String name() {
        return "AliasRlePass";
    }

    @Override
    public Result run(MirModule module, PassContext context) {
        int total = 0;
        Map<Integer, MirFunction> updated = new LinkedHashMap<>(module.functions().size() * 2);
        for (Map.Entry<Integer, MirFunction> e : module.functions().entrySet()) {
            FnResult r = runOnFunction(e.getValue());
            updated.put(e.getKey(), r.fn);
            total += r.rewrites;
        }
        return total == 0 ? Result.unchanged(module) : new Result(module.withFunctions(updated), total);
    }

    private record FnResult(MirFunction fn, int rewrites) {
    }

    private record FieldLoc(int recvId, int classId, int memberIndex, String memberName) {
    }

    private record JavaFieldLoc(int recvId, Field field) {
    }

    private record KnownVal(MirVal value, MirEffect.AliasClass alias) {
    }

    private static FnResult runOnFunction(MirFunction fn) {
        MirCfg cfg = MirCfg.analyze(fn);
        Map<Integer, MirBlock> blockMap = fn.blockMap();
        Map<Integer, MirVal> subst = new HashMap<>();
        Map<Integer, List<MirInst>> outInsts = new HashMap<>();
        int[] rewrites = {0};

        visitDom(fn.entryBlockId(), cfg, blockMap, new HashMap<>(), new HashMap<>(),
                subst, outInsts, rewrites);

        if (rewrites[0] == 0) {
            return new FnResult(fn, 0);
        }
        List<MirBlock> out = new ArrayList<>(fn.blocks().size());
        for (MirBlock b : fn.blocks()) {
            List<MirInst> insts = outInsts.getOrDefault(b.id(), b.insts());
            MirTerminator term = b.terminator().remapUses(v -> resolve(subst, v));
            out.add(new MirBlock(b.id(), b.params(), insts, term, b.handlers(), b.handlerEntry()));
        }
        return new FnResult(fn.withBlocks(out, fn.nextValId(), fn.nextBlockId()), rewrites[0]);
    }

    private static void visitDom(int blockId, MirCfg cfg, Map<Integer, MirBlock> blockMap,
                                 Map<FieldLoc, KnownVal> parentFields,
                                 Map<JavaFieldLoc, KnownVal> parentJavaFields,
                                 Map<Integer, MirVal> subst,
                                 Map<Integer, List<MirInst>> outInsts,
                                 int[] rewrites) {
        MirBlock b = blockMap.get(blockId);
        if (b == null) return;
        Map<FieldLoc, KnownVal> fields = new HashMap<>(parentFields);
        Map<JavaFieldLoc, KnownVal> jfields = new HashMap<>(parentJavaFields);

        // At join points or handler entries, only FROZEN entries remain guaranteed valid.
        if (b.handlerEntry() || cfg.predecessors(blockId).size() > 1) {
            invalidateNonFrozen(fields);
            invalidateNonFrozen(jfields);
        }

        List<MirInst> kept = new ArrayList<>(b.insts().size());
        for (MirInst raw : b.insts()) {
            MirInst inst = raw.remapUses(v -> resolve(subst, v));
            if (inst instanceof MirInst.LoadField lf) {
                FieldLoc loc = new FieldLoc(lf.receiver().id(), lf.classId(), lf.memberIndex(), lf.memberName());
                KnownVal known = fields.get(loc);
                if (known != null && known.value.kind() == lf.result().kind()) {
                    subst.put(lf.result().id(), known.value);
                    rewrites[0]++;
                    continue;
                }
                fields.put(loc, new KnownVal(lf.result(), lf.effect().alias()));
                kept.add(inst);
                continue;
            }
            if (inst instanceof MirInst.StoreField sf) {
                invalidateOverlapping(fields, sf.effect());
                invalidateOverlapping(jfields, sf.effect());
                if (sf.classId() >= 0 && sf.memberIndex() >= 0) {
                    FieldLoc loc = new FieldLoc(sf.receiver().id(), sf.classId(), sf.memberIndex(), sf.memberName());
                    fields.put(loc, new KnownVal(sf.value(), sf.effect().alias()));
                }
                kept.add(inst);
                continue;
            }
            if (inst instanceof MirInst.LoadJavaField lj) {
                int rid = lj.receiver() != null ? lj.receiver().id() : -1;
                JavaFieldLoc loc = new JavaFieldLoc(rid, lj.target().field());
                KnownVal known = jfields.get(loc);
                if (known != null && known.value.kind() == lj.result().kind()) {
                    subst.put(lj.result().id(), known.value);
                    rewrites[0]++;
                    continue;
                }
                jfields.put(loc, new KnownVal(lj.result(), lj.effect().alias()));
                kept.add(inst);
                continue;
            }
            if (inst instanceof MirInst.StoreJavaField sj) {
                invalidateOverlapping(fields, sj.effect());
                invalidateOverlapping(jfields, sj.effect());
                if (sj.value().kind().isPrimitive()) {
                    int rid = sj.receiver() != null ? sj.receiver().id() : -1;
                    jfields.put(new JavaFieldLoc(rid, sj.target().field()), new KnownVal(sj.value(), sj.effect().alias()));
                }
                kept.add(inst);
                continue;
            }
            if (inst.effect().kind() == MirEffect.Kind.WRITE || inst.effect().kind() == MirEffect.Kind.IMPURE) {
                invalidateOverlapping(fields, inst.effect());
                invalidateOverlapping(jfields, inst.effect());
            }
            kept.add(inst);
        }
        outInsts.put(blockId, kept);

        boolean hasHandlers = !b.handlers().isEmpty();
        for (int childId : cfg.domChildren(blockId)) {
            MirBlock child = blockMap.get(childId);
            boolean normalSinglePred = !hasHandlers
                    || (child != null && !child.handlerEntry() && cfg.predecessors(childId).size() == 1);
            visitDom(childId, cfg, blockMap,
                    normalSinglePred ? fields : parentFields,
                    normalSinglePred ? jfields : parentJavaFields,
                    subst, outInsts, rewrites);
        }
    }

    private static <K> void invalidateNonFrozen(Map<K, KnownVal> map) {
        map.entrySet().removeIf(e -> !(e.getValue().alias instanceof MirEffect.AliasClass.Frozen));
    }

    private static <K> void invalidateOverlapping(Map<K, KnownVal> map, MirEffect writeEffect) {
        map.entrySet().removeIf(e -> writeEffect.isMemoryBarrierFor(e.getValue().alias));
    }

    private static MirVal resolve(Map<Integer, MirVal> subst, MirVal v) {
        MirVal cur = v;
        while (subst.containsKey(cur.id())) {
            cur = subst.get(cur.id());
        }
        return cur;
    }
}
