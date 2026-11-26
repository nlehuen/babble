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
 * in SSA {@link MirModule} ({@code BIP014-I3} §6.4).
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

            // Try Shape A: b0: BrIf(c1, b1, [], bFalse, fArgs) ; b1: BrIf(c2, bTrue, tArgs, bFalse, fArgs)
            if (bi0.thenArgs().isEmpty()) {
                MirBlock b1 = blockMap.get(bi0.thenBlock());
                if (canPair(b0, b1, cfg, replaced) && b1.terminator() instanceof MirTerminator.BrIf bi1
                        && bi1.elseBlock() == bi0.elseBlock() && bi1.elseArgs().equals(bi0.elseArgs())) {
                    CondSplit split = splitPureCondSlice(b0, bi0.cond(), useCounts);
                    if (split != null && blockCost(b1.insts()) < blockCost(split.slice)
                            && !dependsOn(b1.insts(), bi1.cond(), split.slice)) {
                        List<MirInst> newB0Insts = new ArrayList<>(split.prefix.size() + b1.insts().size());
                        newB0Insts.addAll(split.prefix);
                        newB0Insts.addAll(b1.insts());
                        MirTerminator newB0Term = new MirTerminator.BrIf(
                                bi1.cond(), b1.id(), List.of(), bi0.elseBlock(), bi0.elseArgs(), bi0.line());
                        MirTerminator newB1Term = new MirTerminator.BrIf(
                                bi0.cond(), bi1.thenBlock(), bi1.thenArgs(), bi1.elseBlock(), bi1.elseArgs(), bi1.line());
                        replaced.put(b0.id(), b0.withInstsAndTerminator(newB0Insts, newB0Term));
                        replaced.put(b1.id(), b1.withInstsAndTerminator(List.copyOf(split.slice), newB1Term));
                        rewrites++;
                        continue;
                    }
                }
            }

            // Try Shape B (short-circuit `and`):
            //   b0: BrIf(c1, b1, [], bJoin, [falseConst]) ; b1: Br(bJoin, [c2])
            if (bi0.thenArgs().isEmpty() && bi0.elseArgs().size() == 1) {
                MirBlock b1 = blockMap.get(bi0.thenBlock());
                if (canPair(b0, b1, cfg, replaced) && b1.terminator() instanceof MirTerminator.Br br1
                        && br1.targetBlock() == bi0.elseBlock() && br1.args().size() == 1
                        && isConstBool(b0, bi0.elseArgs().get(0), false)) {
                    MirVal c2 = br1.args().get(0);
                    CondSplit split = splitPureCondSlice(b0, bi0.cond(), useCounts);
                    if (split != null && blockCost(b1.insts()) < blockCost(split.slice)
                            && !dependsOn(b1.insts(), c2, split.slice)) {
                        List<MirInst> newB0Insts = new ArrayList<>(split.prefix.size() + b1.insts().size());
                        newB0Insts.addAll(split.prefix);
                        newB0Insts.addAll(b1.insts());
                        MirTerminator newB0Term = new MirTerminator.BrIf(
                                c2, b1.id(), List.of(), bi0.elseBlock(), bi0.elseArgs(), bi0.line());
                        MirTerminator newB1Term = new MirTerminator.Br(
                                br1.targetBlock(), List.of(bi0.cond()), br1.line());
                        replaced.put(b0.id(), b0.withInstsAndTerminator(newB0Insts, newB0Term));
                        replaced.put(b1.id(), b1.withInstsAndTerminator(List.copyOf(split.slice), newB1Term));
                        rewrites++;
                        continue;
                    }
                }
            }

            // Try Shape C (short-circuit `or`):
            //   b0: BrIf(c1, bJoin, [trueConst], b1, []) ; b1: Br(bJoin, [c2])
            if (bi0.elseArgs().isEmpty() && bi0.thenArgs().size() == 1) {
                MirBlock b1 = blockMap.get(bi0.elseBlock());
                if (canPair(b0, b1, cfg, replaced) && b1.terminator() instanceof MirTerminator.Br br1
                        && br1.targetBlock() == bi0.thenBlock() && br1.args().size() == 1
                        && isConstBool(b0, bi0.thenArgs().get(0), true)) {
                    MirVal c2 = br1.args().get(0);
                    CondSplit split = splitPureCondSlice(b0, bi0.cond(), useCounts);
                    if (split != null && blockCost(b1.insts()) < blockCost(split.slice)
                            && !dependsOn(b1.insts(), c2, split.slice)) {
                        List<MirInst> newB0Insts = new ArrayList<>(split.prefix.size() + b1.insts().size());
                        newB0Insts.addAll(split.prefix);
                        newB0Insts.addAll(b1.insts());
                        MirTerminator newB0Term = new MirTerminator.BrIf(
                                c2, bi0.thenBlock(), bi0.thenArgs(), b1.id(), List.of(), bi0.line());
                        MirTerminator newB1Term = new MirTerminator.Br(
                                br1.targetBlock(), List.of(bi0.cond()), br1.line());
                        replaced.put(b0.id(), b0.withInstsAndTerminator(newB0Insts, newB0Term));
                        replaced.put(b1.id(), b1.withInstsAndTerminator(List.copyOf(split.slice), newB1Term));
                        rewrites++;
                    }
                }
            }
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

    private static boolean canPair(MirBlock b0, MirBlock b1, MirCfg cfg, Map<Integer, MirBlock> replaced) {
        if (b1 == null || replaced.containsKey(b1.id()) || b1.id() == b0.id()) return false;
        if (b1.handlerEntry() || !b1.params().isEmpty() || !b1.handlers().equals(b0.handlers())) return false;
        if (cfg.predecessors(b1.id()).size() != 1) return false;
        if (b1.insts().isEmpty()) return false;
        return isPureSafeBlock(b1);
    }

    private static boolean isConstBool(MirBlock b0, MirVal val, boolean expected) {
        for (MirInst inst : b0.insts()) {
            if (inst instanceof MirInst.Const c && c.result().id() == val.id()) {
                return Boolean.valueOf(expected).equals(c.value());
            }
        }
        return false;
    }

    private static boolean dependsOn(List<MirInst> insts, MirVal termVal, List<MirInst> c1Slice) {
        Set<Integer> c1Defs = new HashSet<>();
        for (MirInst inst : c1Slice) {
            if (inst.result() != null) c1Defs.add(inst.result().id());
        }
        if (c1Defs.contains(termVal.id())) return true;
        for (MirInst inst : insts) {
            for (MirVal u : inst.usedValues()) {
                if (c1Defs.contains(u.id())) return true;
            }
        }
        return false;
    }

    private static boolean isPureSafeBlock(MirBlock b) {
        for (MirInst inst : b.insts()) {
            if (inst instanceof MirInst.Narrow || inst.result() == null || !inst.effect().isPureFloatable()) {
                return false;
            }
        }
        return true;
    }

    private record CondSplit(List<MirInst> prefix, List<MirInst> slice) {
    }

    /**
     * Partition {@code b0}'s instructions into {@code prefix} (kept in {@code b0}) and {@code slice}
     * (pure instructions solely feeding {@code cond} that can move to {@code b1}). Any pure instruction
     * in {@code slice} must only be used inside {@code slice} or by {@code b0}'s terminator {@code cond},
     * and must not be depended upon by any instruction left in {@code prefix}.
     */
    private static CondSplit splitPureCondSlice(MirBlock b0, MirVal cond, Map<Integer, Integer> useCounts) {
        List<MirInst> insts = b0.insts();
        if (insts.isEmpty()) return null;
        Set<Integer> needed = new HashSet<>();
        needed.add(cond.id());
        Map<Integer, Integer> internalUses = new HashMap<>();
        internalUses.put(cond.id(), 1);
        boolean[] inSlice = new boolean[insts.size()];
        int sliceCount = 0;
        for (int i = insts.size() - 1; i >= 0; i--) {
            MirInst inst = insts.get(i);
            MirVal res = inst.result();
            if (res == null || !needed.contains(res.id())) {
                continue;
            }
            if (inst instanceof MirInst.Narrow || !inst.effect().isPureFloatable()) {
                return null;
            }
            if (useCounts.getOrDefault(res.id(), 0) != internalUses.getOrDefault(res.id(), 0)) {
                return null;
            }
            inSlice[i] = true;
            sliceCount++;
            for (MirVal u : inst.usedValues()) {
                needed.add(u.id());
                internalUses.merge(u.id(), 1, Integer::sum);
            }
        }
        if (sliceCount == 0) return null;
        List<MirInst> prefix = new ArrayList<>(insts.size() - sliceCount);
        List<MirInst> slice = new ArrayList<>(sliceCount);
        for (int i = 0; i < insts.size(); i++) {
            if (inSlice[i]) slice.add(insts.get(i));
            else prefix.add(insts.get(i));
        }
        return new CondSplit(prefix, slice);
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
                case MirInst.LoadExport ignored -> 3;
                case MirInst.ArrayGet ignored -> 4;
                default -> 1;
            };
        }
        return cost;
    }
}
