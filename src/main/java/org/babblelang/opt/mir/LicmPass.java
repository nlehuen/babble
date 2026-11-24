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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Natural-loop Invariant Code Motion ({@code O6}) over SSA {@link MirModule} ({@code BIP014-P3} §6.4).
 *
 * <p>Hoists loop-invariant {@code PURE} (nothrow, terminating, deterministic) and un-clobbered
 * {@code READ} ({@code FROZEN}, or disjoint {@code UNIQUE_PARAM(k)}) instructions into the loop
 * preheader (creating a dedicated preheader block when needed). Instructions depending on
 * loop-internal {@link MirInst.Narrow} anchors cannot be hoisted because their operand is defined
 * inside the loop body.
 */
public final class LicmPass implements MirPass {
    @Override
    public String name() {
        return "LicmPass";
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

    private static FnResult runOnFunction(MirFunction fn) {
        MirFunction current = fn;
        int totalHoisted = 0;
        MirCfg cfg = MirCfg.analyze(current);
        List<MirCfg.NaturalLoop> loops = cfg.findNaturalLoops();
        if (loops.isEmpty()) {
            return new FnResult(fn, 0);
        }

        for (MirCfg.NaturalLoop loop : loops) {
            if (loop.outsidePredecessors().size() != 1) {
                continue;
            }
            Map<Integer, MirBlock> blockMap = current.blockMap();
            MirBlock header = blockMap.get(loop.headerId());
            if (header == null || header.handlerEntry()) {
                continue;
            }
            int predId = loop.outsidePredecessors().get(0);
            MirBlock pred = blockMap.get(predId);
            if (pred == null || pred.handlerEntry()) {
                continue;
            }

            // Collect definitions inside the loop body and all writes/impure barriers in the loop.
            Set<Integer> loopDefs = new HashSet<>();
            Map<Integer, MirInst.Const> loopConsts = new HashMap<>();
            List<MirEffect> loopBarriers = new ArrayList<>();
            for (int bid : loop.bodyIds()) {
                MirBlock b = blockMap.get(bid);
                if (b == null) continue;
                for (MirVal bp : b.params()) {
                    loopDefs.add(bp.id());
                }
                for (MirInst inst : b.insts()) {
                    if (inst.result() != null) {
                        loopDefs.add(inst.result().id());
                        if (inst instanceof MirInst.Const c) {
                            loopConsts.put(c.result().id(), c);
                        }
                    }
                    if (inst.effect().kind() == MirEffect.Kind.WRITE || inst.effect().kind() == MirEffect.Kind.IMPURE) {
                        loopBarriers.add(inst.effect());
                    }
                }
            }

            // Iteratively discover loop-invariant instructions in RPO order.
            Set<Integer> hoistedIds = new HashSet<>();
            List<MirInst> toHoist = new ArrayList<>();
            Set<Integer> hoistedValIds = new HashSet<>();
            boolean progress = true;
            while (progress) {
                progress = false;
                for (MirBlock b : MirCfg.analyze(current).rpo()) {
                    if (!loop.bodyIds().contains(b.id())) continue;
                    for (MirInst inst : b.insts()) {
                        MirVal res = inst.result();
                        if (res == null || hoistedIds.contains(res.id()) || inst instanceof MirInst.Const) {
                            continue;
                        }
                        if (!isHoistable(inst, loopBarriers)) {
                            continue;
                        }
                        if (operandsInvariant(inst, loopDefs, hoistedValIds, loopConsts)) {
                            // First hoist any loop-local Const operand needed by this instruction.
                            for (MirVal u : inst.usedValues()) {
                                if (loopDefs.contains(u.id()) && !hoistedValIds.contains(u.id())) {
                                    MirInst.Const lc = loopConsts.get(u.id());
                                    if (lc != null && hoistedIds.add(lc.result().id())) {
                                        toHoist.add(lc);
                                        hoistedValIds.add(lc.result().id());
                                    }
                                }
                            }
                            hoistedIds.add(res.id());
                            toHoist.add(inst);
                            hoistedValIds.add(res.id());
                            progress = true;
                        }
                    }
                }
            }

            if (toHoist.isEmpty()) {
                continue;
            }

            // Place hoisted instructions in pred (if unconditional Br to header) or a new preheader block.
            int nextBlockId = current.nextBlockId();
            List<MirBlock> newBlocks = new ArrayList<>(current.blocks().size() + 1);
            MirBlock insertedPreheader = null;
            for (MirBlock b : current.blocks()) {
                if (loop.bodyIds().contains(b.id())) {
                    List<MirInst> kept = new ArrayList<>(b.insts().size());
                    for (MirInst inst : b.insts()) {
                        if (inst.result() == null || !hoistedIds.contains(inst.result().id())) {
                            kept.add(inst);
                        }
                    }
                    newBlocks.add(b.withInsts(kept));
                } else if (b.id() == predId) {
                    if (b.terminator() instanceof MirTerminator.Br br && br.targetBlock() == loop.headerId()) {
                        List<MirInst> combined = new ArrayList<>(b.insts().size() + toHoist.size());
                        combined.addAll(b.insts());
                        combined.addAll(toHoist);
                        newBlocks.add(b.withInsts(combined));
                    } else if (b.terminator() instanceof MirTerminator.BrIf bi
                            && (bi.thenBlock() == loop.headerId() ^ bi.elseBlock() == loop.headerId())) {
                        int preId = nextBlockId++;
                        boolean onThen = bi.thenBlock() == loop.headerId();
                        List<MirVal> edgeArgs = onThen ? bi.thenArgs() : bi.elseArgs();
                        insertedPreheader = new MirBlock(preId, List.of(), toHoist,
                                new MirTerminator.Br(loop.headerId(), edgeArgs, bi.line()),
                                b.handlers(), false);
                        MirTerminator newTerm = onThen
                                ? new MirTerminator.BrIf(bi.cond(), preId, List.of(), bi.elseBlock(), bi.elseArgs(), bi.line())
                                : new MirTerminator.BrIf(bi.cond(), bi.thenBlock(), bi.thenArgs(), preId, List.of(), bi.line());
                        newBlocks.add(b.withTerminator(newTerm));
                    } else {
                        // Fallback: skip hoisting for this loop shape.
                        newBlocks = null;
                        break;
                    }
                } else {
                    newBlocks.add(b);
                }
            }
            if (newBlocks != null) {
                if (insertedPreheader != null) {
                    newBlocks.add(insertedPreheader);
                }
                current = current.withBlocks(newBlocks, current.nextValId(), nextBlockId);
                totalHoisted += toHoist.size();
            }
        }
        return totalHoisted == 0 ? new FnResult(fn, 0) : new FnResult(current, totalHoisted);
    }

    private static boolean isHoistable(MirInst inst, List<MirEffect> loopBarriers) {
        if (inst instanceof MirInst.Narrow || inst instanceof MirInst.SlotRead
                || inst instanceof MirInst.ForIterHasNext || inst instanceof MirInst.ForIterNext
                || inst instanceof MirInst.UnwrapThrown || inst instanceof MirInst.CatchMatch) {
            return false;
        }
        MirEffect fx = inst.effect();
        if (fx.isPureFloatable()) {
            return true;
        }
        if (fx.isReadFloatable()) {
            for (MirEffect barrier : loopBarriers) {
                if (barrier.isMemoryBarrierFor(fx.alias())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean operandsInvariant(MirInst inst, Set<Integer> loopDefs, Set<Integer> hoistedValIds,
                                             Map<Integer, MirInst.Const> loopConsts) {
        for (MirVal u : inst.usedValues()) {
            if (loopDefs.contains(u.id()) && !hoistedValIds.contains(u.id()) && !loopConsts.containsKey(u.id())) {
                return false;
            }
        }
        return true;
    }
}
