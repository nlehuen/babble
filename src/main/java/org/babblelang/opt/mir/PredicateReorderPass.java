package org.babblelang.opt.mir;

import org.babblelang.ir.mir.MirBlock;
import org.babblelang.ir.mir.MirCfg;
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
 * Narrowing-safe Pure Predicate Reordering ({@code O11}) over short-circuit {@code BrIf} chains
 * in SSA {@link MirModule} ({@code BIP014-P3} §6.4).
 *
 * <p>Reorders {@code expensivePure(x) && cheapScalar(y)} into {@code cheapScalar(y) && expensivePure(x)}
 * whenever both predicates are pure, nothrow, terminating, and deterministic, and the second block
 * contains no {@link MirInst.Narrow} anchor (structurally preventing narrowed dereferences such as
 * {@code s != null && s.length() > 0} from ever moving ahead of their guard).
 */
public final class PredicateReorderPass implements MirPass {
    @Override
    public String name() {
        return "PredicateReorderPass";
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
        MirCfg cfg = MirCfg.analyze(fn);
        Map<Integer, MirBlock> blockMap = fn.blockMap();

        // Count total uses of every SSA value across the function so we know whether B0's predicate
        // slice is exclusively consumed by B0's BrIf.
        Map<Integer, Integer> useCounts = new HashMap<>();
        for (MirBlock b : cfg.rpo()) {
            for (MirInst inst : b.insts()) {
                for (MirVal u : inst.usedValues()) {
                    useCounts.merge(u.id(), 1, Integer::sum);
                }
            }
            for (MirVal u : b.terminator().usedValues()) {
                useCounts.merge(u.id(), 1, Integer::sum);
            }
        }

        int rewrites = 0;
        Map<Integer, MirBlock> replaced = new HashMap<>();

        for (MirBlock b0 : cfg.rpo()) {
            if (replaced.containsKey(b0.id())) continue;
            if (!(b0.terminator() instanceof MirTerminator.BrIf bi0)) continue;
            if (!bi0.thenArgs().isEmpty()) continue;

            MirBlock b1 = blockMap.get(bi0.thenBlock());
            if (b1 == null || replaced.containsKey(b1.id()) || b1.id() == b0.id()) continue;
            if (b1.handlerEntry() || !b1.params().isEmpty() || !b1.handlers().equals(b0.handlers())) continue;
            if (cfg.predecessors(b1.id()).size() != 1) continue;
            if (!(b1.terminator() instanceof MirTerminator.BrIf bi1)) continue;
            if (bi1.elseBlock() != bi0.elseBlock() || !bi1.elseArgs().equals(bi0.elseArgs())) continue;
            if (b1.insts().isEmpty()) continue;

            // B1 must contain only pure nothrow terminating deterministic instructions and NO Narrow anchors.
            if (!isPureSafeBlock(b1)) continue;

            // Find the pure-only suffix of B0 computing bi0.cond() (not used anywhere outside that suffix + bi0.cond()).
            int sliceStart = findPureCondSliceStart(b0, bi0.cond(), useCounts, b1);
            if (sliceStart < 0) continue;

            List<MirInst> c1Slice = b0.insts().subList(sliceStart, b0.insts().size());
            List<MirInst> c2Insts = b1.insts();
            if (blockCost(c2Insts) >= blockCost(c1Slice)) {
                continue;
            }

            // Ensure B1's instructions do not reference any value defined in c1Slice.
            Set<Integer> c1Defs = new HashSet<>();
            for (MirInst inst : c1Slice) {
                if (inst.result() != null) c1Defs.add(inst.result().id());
            }
            boolean dependsOnC1 = c1Defs.contains(bi1.cond().id());
            for (MirInst inst : c2Insts) {
                for (MirVal u : inst.usedValues()) {
                    if (c1Defs.contains(u.id())) {
                        dependsOnC1 = true;
                        break;
                    }
                }
            }
            if (dependsOnC1) continue;

            // Swap: B0 runs prefix + c2Insts then BrIf(c2, B1, [], BFalse, fArgs);
            //       B1 runs c1Slice then BrIf(c1, BTrue, tArgs, BFalse, fArgs).
            List<MirInst> newB0Insts = new ArrayList<>(sliceStart + c2Insts.size());
            newB0Insts.addAll(b0.insts().subList(0, sliceStart));
            newB0Insts.addAll(c2Insts);
            MirTerminator newB0Term = new MirTerminator.BrIf(
                    bi1.cond(), b1.id(), List.of(), bi0.elseBlock(), bi0.elseArgs(), bi0.line());

            MirTerminator newB1Term = new MirTerminator.BrIf(
                    bi0.cond(), bi1.thenBlock(), bi1.thenArgs(), bi1.elseBlock(), bi1.elseArgs(), bi1.line());

            replaced.put(b0.id(), b0.withInstsAndTerminator(newB0Insts, newB0Term));
            replaced.put(b1.id(), b1.withInstsAndTerminator(List.copyOf(c1Slice), newB1Term));
            rewrites++;
        }

        if (rewrites == 0) {
            return new FnResult(fn, 0);
        }
        List<MirBlock> out = new ArrayList<>(fn.blocks().size());
        for (MirBlock b : fn.blocks()) {
            out.add(replaced.getOrDefault(b.id(), b));
        }
        return new FnResult(fn.withBlocks(out, fn.nextValId(), fn.nextBlockId()), rewrites);
    }

    private static boolean isPureSafeBlock(MirBlock b) {
        for (MirInst inst : b.insts()) {
            if (inst instanceof MirInst.Narrow || inst.result() == null || !inst.effect().isPureFloatable()) {
                return false;
            }
        }
        return true;
    }

    private static int findPureCondSliceStart(MirBlock b0, MirVal cond, Map<Integer, Integer> useCounts, MirBlock b1) {
        List<MirInst> insts = b0.insts();
        if (insts.isEmpty()) return -1;
        // Walk backwards from the end of B0 collecting pure instructions solely feeding cond.
        Set<Integer> needed = new HashSet<>();
        needed.add(cond.id());
        Map<Integer, Integer> internalUses = new HashMap<>();
        internalUses.put(cond.id(), 1);
        int start = insts.size();
        for (int i = insts.size() - 1; i >= 0; i--) {
            MirInst inst = insts.get(i);
            MirVal res = inst.result();
            if (res == null || !needed.contains(res.id())) {
                break;
            }
            if (inst instanceof MirInst.Narrow || !inst.effect().isPureFloatable()) {
                return -1;
            }
            if (useCounts.getOrDefault(res.id(), 0) != internalUses.getOrDefault(res.id(), 0)) {
                return -1;
            }
            start = i;
            for (MirVal u : inst.usedValues()) {
                needed.add(u.id());
                internalUses.merge(u.id(), 1, Integer::sum);
            }
        }
        return start < insts.size() ? start : -1;
    }

    private static int blockCost(List<MirInst> insts) {
        int cost = 0;
        for (MirInst inst : insts) {
            cost += switch (inst) {
                case MirInst.Const ignored -> 0;
                case MirInst.Copy ignored -> 0;
                case MirInst.CallBabble ignored -> 10;
                case MirInst.CallMethod ignored -> 10;
                case MirInst.CallInterface ignored -> 10;
                case MirInst.CallJava ignored -> 10;
                case MirInst.LoadField ignored -> 4;
                case MirInst.LoadJavaField ignored -> 4;
                case MirInst.ArrayGet ignored -> 4;
                default -> 1;
            };
        }
        return cost;
    }
}
