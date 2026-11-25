package org.babblelang.opt.mir;

import org.babblelang.ir.mir.MirBlock;
import org.babblelang.ir.mir.MirCfg;
import org.babblelang.ir.mir.MirFunction;
import org.babblelang.ir.mir.MirInst;
import org.babblelang.ir.mir.MirModule;
import org.babblelang.ir.mir.MirTerminator;
import org.babblelang.ir.mir.MirVal;
import org.babblelang.opt.PassContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dead Code, Dead Block-Parameter, Unreachable-Block, and Empty-Trampoline Elimination
 * ({@code O4}) over SSA {@link MirModule} ({@code BIP014-I3} §6.4).
 */
public final class MirDcePass implements MirPass {
    @Override
    public String name() {
        return "MirDcePass";
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

    private record ParamCoord(int blockId, int index) {
    }

    private static FnResult runOnFunction(MirFunction fn) {
        int rewrites = 0;
        MirCfg cfg = MirCfg.analyze(fn);
        List<MirBlock> rpo = cfg.rpo();
        if (rpo.size() < fn.blocks().size()) {
            rewrites += (fn.blocks().size() - rpo.size());
        }

        // Index definitions and incoming edge args.
        Map<Integer, MirInst> defInst = new HashMap<>();
        Map<Integer, ParamCoord> defParam = new HashMap<>();
        Map<Integer, List<List<MirVal>>> incomingEdgeArgs = new HashMap<>();
        for (MirBlock b : rpo) {
            incomingEdgeArgs.put(b.id(), new ArrayList<>());
            for (int i = 0; i < b.params().size(); i++) {
                defParam.put(b.params().get(i).id(), new ParamCoord(b.id(), i));
            }
            for (MirInst inst : b.insts()) {
                if (inst.result() != null) {
                    defInst.put(inst.result().id(), inst);
                }
            }
        }
        for (MirBlock b : rpo) {
            if (b.terminator() instanceof MirTerminator.Br br) {
                List<List<MirVal>> list = incomingEdgeArgs.get(br.targetBlock());
                if (list != null) list.add(br.args());
            } else if (b.terminator() instanceof MirTerminator.BrIf bi) {
                List<List<MirVal>> tl = incomingEdgeArgs.get(bi.thenBlock());
                if (tl != null) tl.add(bi.thenArgs());
                List<List<MirVal>> el = incomingEdgeArgs.get(bi.elseBlock());
                if (el != null) el.add(bi.elseArgs());
            }
        }

        // Worklist mark-sweep over live SSA values.
        Set<Integer> liveVals = new HashSet<>();
        Deque<Integer> work = new ArrayDeque<>();
        for (MirBlock b : rpo) {
            if (b.handlerEntry() && !b.params().isEmpty()) {
                markLive(b.params().get(0).id(), liveVals, work);
            }
            for (MirInst inst : b.insts()) {
                if (!inst.effect().isRemovableIfUnused()) {
                    if (inst.result() != null) {
                        markLive(inst.result().id(), liveVals, work);
                    }
                    for (MirVal u : inst.usedValues()) {
                        markLive(u.id(), liveVals, work);
                    }
                }
            }
            MirTerminator t = b.terminator();
            if (t instanceof MirTerminator.BrIf bi) {
                markLive(bi.cond().id(), liveVals, work);
            } else if (!(t instanceof MirTerminator.Br)) {
                for (MirVal u : t.usedValues()) {
                    markLive(u.id(), liveVals, work);
                }
            }
        }

        while (!work.isEmpty()) {
            int vid = work.poll();
            MirInst di = defInst.get(vid);
            if (di != null) {
                for (MirVal u : di.usedValues()) {
                    markLive(u.id(), liveVals, work);
                }
                continue;
            }
            ParamCoord pc = defParam.get(vid);
            if (pc != null) {
                List<List<MirVal>> edges = incomingEdgeArgs.get(pc.blockId);
                if (edges != null) {
                    for (List<MirVal> args : edges) {
                        if (pc.index < args.size()) {
                            markLive(args.get(pc.index).id(), liveVals, work);
                        }
                    }
                }
            }
        }

        // Identify dead block-parameter indices per block.
        Map<Integer, Set<Integer>> deadParamIndices = new HashMap<>();
        for (MirBlock b : rpo) {
            if (b.handlerEntry() || b.params().isEmpty()) continue;
            for (int i = 0; i < b.params().size(); i++) {
                if (!liveVals.contains(b.params().get(i).id())) {
                    deadParamIndices.computeIfAbsent(b.id(), k -> new HashSet<>()).add(i);
                    rewrites++;
                }
            }
        }

        // Sweep dead instructions and dead block params.
        List<MirBlock> swept = new ArrayList<>(rpo.size());
        for (MirBlock b : rpo) {
            Set<Integer> dropP = deadParamIndices.getOrDefault(b.id(), Set.of());
            List<MirVal> newParams = dropP.isEmpty() ? b.params() : filterOut(b.params(), dropP);
            List<MirInst> newInsts = new ArrayList<>(b.insts().size());
            for (MirInst inst : b.insts()) {
                if (inst.result() != null && inst.effect().isRemovableIfUnused()
                        && !liveVals.contains(inst.result().id())) {
                    rewrites++;
                    continue;
                }
                newInsts.add(inst);
            }
            MirTerminator term = b.terminator();
            if (term instanceof MirTerminator.Br br) {
                Set<Integer> td = deadParamIndices.getOrDefault(br.targetBlock(), Set.of());
                if (!td.isEmpty()) {
                    term = new MirTerminator.Br(br.targetBlock(), filterOut(br.args(), td), br.line());
                }
            } else if (term instanceof MirTerminator.BrIf bi) {
                Set<Integer> td = deadParamIndices.getOrDefault(bi.thenBlock(), Set.of());
                Set<Integer> ed = deadParamIndices.getOrDefault(bi.elseBlock(), Set.of());
                List<MirVal> tArgs = td.isEmpty() ? bi.thenArgs() : filterOut(bi.thenArgs(), td);
                List<MirVal> eArgs = ed.isEmpty() ? bi.elseArgs() : filterOut(bi.elseArgs(), ed);
                if (bi.thenBlock() == bi.elseBlock() && tArgs.equals(eArgs)) {
                    term = new MirTerminator.Br(bi.thenBlock(), tArgs, bi.line());
                    rewrites++;
                } else if (!td.isEmpty() || !ed.isEmpty()) {
                    term = new MirTerminator.BrIf(bi.cond(), bi.thenBlock(), tArgs, bi.elseBlock(), eArgs, bi.line());
                }
            }
            swept.add(new MirBlock(b.id(), newParams, newInsts, term, b.handlers(), b.handlerEntry()));
        }

        // Thread empty zero-param trampoline blocks: bbX: br bbY -> redirect predecessors directly to bbY.
        Map<Integer, Integer> redirect = new HashMap<>();
        for (MirBlock b : swept) {
            if (b.id() != fn.entryBlockId() && !b.handlerEntry()
                    && b.params().isEmpty() && b.insts().isEmpty()
                    && b.terminator() instanceof MirTerminator.Br br
                    && br.args().isEmpty() && br.targetBlock() != b.id()) {
                redirect.put(b.id(), br.targetBlock());
            }
        }
        if (!redirect.isEmpty()) {
            List<MirBlock> threaded = new ArrayList<>(swept.size());
            boolean anyThreaded = false;
            for (MirBlock b : swept) {
                MirTerminator term = b.terminator();
                if (term instanceof MirTerminator.Br br && br.args().isEmpty()) {
                    int tgt = followRedirect(redirect, br.targetBlock());
                    if (tgt != br.targetBlock()) {
                        term = new MirTerminator.Br(tgt, List.of(), br.line());
                        anyThreaded = true;
                        rewrites++;
                    }
                } else if (term instanceof MirTerminator.BrIf bi) {
                    int tt = bi.thenArgs().isEmpty() ? followRedirect(redirect, bi.thenBlock()) : bi.thenBlock();
                    int et = bi.elseArgs().isEmpty() ? followRedirect(redirect, bi.elseBlock()) : bi.elseBlock();
                    if (tt != bi.thenBlock() || et != bi.elseBlock()) {
                        term = new MirTerminator.BrIf(bi.cond(), tt, bi.thenArgs(), et, bi.elseArgs(), bi.line());
                        anyThreaded = true;
                        rewrites++;
                    }
                }
                threaded.add(term == b.terminator() ? b : b.withTerminator(term));
            }
            if (anyThreaded) {
                MirFunction tmp = fn.withBlocks(threaded, fn.nextValId(), fn.nextBlockId());
                swept = MirCfg.analyze(tmp).rpo();
            }
        }

        return rewrites == 0 ? new FnResult(fn, 0)
                : new FnResult(fn.withBlocks(swept, fn.nextValId(), fn.nextBlockId()), rewrites);
    }

    private static int followRedirect(Map<Integer, Integer> redirect, int target) {
        int cur = target;
        Set<Integer> seen = new HashSet<>();
        while (redirect.containsKey(cur) && seen.add(cur)) {
            cur = redirect.get(cur);
        }
        return cur;
    }

    private static void markLive(int valId, Set<Integer> live, Deque<Integer> work) {
        if (live.add(valId)) {
            work.add(valId);
        }
    }

    private static List<MirVal> filterOut(List<MirVal> list, Set<Integer> dropIndices) {
        List<MirVal> out = new ArrayList<>(list.size() - dropIndices.size());
        for (int i = 0; i < list.size(); i++) {
            if (!dropIndices.contains(i)) {
                out.add(list.get(i));
            }
        }
        return out;
    }
}
