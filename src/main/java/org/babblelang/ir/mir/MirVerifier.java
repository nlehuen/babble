package org.babblelang.ir.mir;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural and SSA dominance verifier for {@link MirModule} ({@code BIP014-I3} §6.5).
 *
 * <p>Runs immediately after {@link MirLowerer} and after every modifying {@code MirPass} whenever
 * {@code -Dbabble.verify=true} is active. Checks:
 *
 * <ul>
 *   <li>Unique block IDs, valid entry block, valid terminator and handler target block IDs.
 *   <li>Block-parameter arity and {@link org.babblelang.analysis.PrimitiveKind} matching on every
 *       {@link MirTerminator.Br} and {@link MirTerminator.BrIf} edge.
 *   <li>Unique SSA value definitions across function params, block params, and instruction results.
 *   <li>Strict SSA dominance: every value use in a reachable block is dominated by its definition
 *       (with intra-block ordering checked for same-block defs/uses).
 *   <li>Handler-entry blocks declare at least one reference parameter for the caught exception.
 * </ul>
 */
public final class MirVerifier {
    private MirVerifier() {}

    public static void verify(MirModule module) {
        if (module == null) {
            throw new IllegalStateException("MIR verification failed : null MirModule");
        }
        for (MirFunction fn : module.functions().values()) {
            verifyFunction(fn);
        }
    }

    public static void verifyFunction(MirFunction fn) {
        String label = fn.name() != null ? fn.name() : ("fn#" + fn.hirFunctionId());
        if (fn.blocks().isEmpty()) {
            throw fail(label, "function has no basic blocks");
        }
        Map<Integer, MirBlock> blockMap = new HashMap<>(fn.blocks().size() * 2);
        for (MirBlock b : fn.blocks()) {
            if (blockMap.put(b.id(), b) != null) {
                throw fail(label, "duplicate block id bb" + b.id());
            }
        }
        if (!blockMap.containsKey(fn.entryBlockId())) {
            throw fail(label, "missing entry block bb" + fn.entryBlockId());
        }

        // 1. Collect unique SSA definitions and their (blockId, position) coordinates.
        //    Function parameters have defBlock = -1, defPos = -1 (dominate everything).
        //    Block parameters have defBlock = block.id, defPos = -1 (dominate all insts in block).
        //    Instruction i has defBlock = block.id, defPos = i.
        Map<Integer, MirVal> definedVals = new HashMap<>();
        Map<Integer, Integer> defBlock = new HashMap<>();
        Map<Integer, Integer> defPos = new HashMap<>();

        for (MirVal p : fn.params()) {
            if (p != null) {
                recordDef(label, p, -1, -1, definedVals, defBlock, defPos);
            }
        }
        for (MirBlock b : fn.blocks()) {
            if (b.handlerEntry() && b.params().isEmpty()) {
                throw fail(label, "handler entry bb" + b.id() + " missing exception parameter");
            }
            for (MirVal bp : b.params()) {
                recordDef(label, bp, b.id(), -1, definedVals, defBlock, defPos);
            }
            for (int i = 0; i < b.insts().size(); i++) {
                MirVal res = b.insts().get(i).result();
                if (res != null) {
                    recordDef(label, res, b.id(), i, definedVals, defBlock, defPos);
                }
            }
        }

        // 2. Verify CFG edges, block-parameter arity/kinds, handler targets, and SSA dominance.
        MirCfg cfg = MirCfg.analyze(fn);
        Set<Integer> reachableIds = new HashSet<>();
        for (MirBlock b : cfg.rpo()) {
            reachableIds.add(b.id());
        }

        for (MirBlock b : fn.blocks()) {
            boolean reachable = reachableIds.contains(b.id());
            for (MirHandler h : b.handlers()) {
                MirBlock target = blockMap.get(h.handlerBlockId());
                if (target == null) {
                    throw fail(
                            label,
                            "bb" + b.id() + " references missing handler bb" + h.handlerBlockId());
                }
                if (!target.handlerEntry()) {
                    throw fail(
                            label,
                            "bb"
                                    + b.id()
                                    + " handler target bb"
                                    + h.handlerBlockId()
                                    + " is not marked handlerEntry");
                }
            }
            if (reachable) {
                for (int i = 0; i < b.insts().size(); i++) {
                    MirInst inst = b.insts().get(i);
                    for (MirVal use : inst.usedValues()) {
                        checkUseDominated(
                                label, use, b.id(), i, definedVals, defBlock, defPos, cfg);
                    }
                }
                MirTerminator term = b.terminator();
                for (MirVal use : term.usedValues()) {
                    checkUseDominated(
                            label,
                            use,
                            b.id(),
                            b.insts().size(),
                            definedVals,
                            defBlock,
                            defPos,
                            cfg);
                }
            }
            verifyTerminatorEdges(label, b, blockMap);
        }
    }

    private static void recordDef(
            String label,
            MirVal val,
            int blk,
            int pos,
            Map<Integer, MirVal> definedVals,
            Map<Integer, Integer> defBlock,
            Map<Integer, Integer> defPos) {
        if (definedVals.put(val.id(), val) != null) {
            throw fail(label, "duplicate SSA definition for v" + val.id());
        }
        defBlock.put(val.id(), blk);
        defPos.put(val.id(), pos);
    }

    private static void checkUseDominated(
            String label,
            MirVal use,
            int useBlock,
            int usePos,
            Map<Integer, MirVal> definedVals,
            Map<Integer, Integer> defBlock,
            Map<Integer, Integer> defPos,
            MirCfg cfg) {
        MirVal def = definedVals.get(use.id());
        if (def == null) {
            throw fail(label, "bb" + useBlock + " uses undefined SSA value v" + use.id());
        }
        if (def.kind() != use.kind()) {
            throw fail(
                    label,
                    "bb"
                            + useBlock
                            + " uses v"
                            + use.id()
                            + " as "
                            + use.kind()
                            + " but defined as "
                            + def.kind());
        }
        int dBlk = defBlock.get(use.id());
        if (dBlk < 0) {
            return; // function parameter dominates entire function
        }
        if (dBlk == useBlock) {
            int dPos = defPos.get(use.id());
            if (dPos >= usePos) {
                throw fail(
                        label,
                        "bb"
                                + useBlock
                                + " uses v"
                                + use.id()
                                + " before its definition at inst "
                                + dPos);
            }
            return;
        }
        if (!cfg.dominates(dBlk, useBlock)) {
            throw fail(
                    label,
                    "bb"
                            + useBlock
                            + " uses v"
                            + use.id()
                            + " defined in non-dominating bb"
                            + dBlk);
        }
    }

    private static void verifyTerminatorEdges(
            String label, MirBlock b, Map<Integer, MirBlock> blockMap) {
        MirTerminator term = b.terminator();
        if (term instanceof MirTerminator.Br br) {
            verifyEdge(label, b.id(), br.targetBlock(), br.args(), blockMap);
        } else if (term instanceof MirTerminator.BrIf brIf) {
            verifyEdge(label, b.id(), brIf.thenBlock(), brIf.thenArgs(), blockMap);
            verifyEdge(label, b.id(), brIf.elseBlock(), brIf.elseArgs(), blockMap);
        } else if (term instanceof MirTerminator.Switch sw) {
            for (int i = 0; i < sw.keys().length; i++) {
                if (i > 0 && sw.keys()[i] <= sw.keys()[i - 1]) {
                    throw fail(label, "bb" + b.id() + " switch keys must be strictly ascending");
                }
                verifyEdge(label, b.id(), sw.targetBlocks()[i], sw.targetArgs().get(i), blockMap);
            }
            verifyEdge(label, b.id(), sw.defaultBlock(), sw.defaultArgs(), blockMap);
        }
    }

    private static void verifyEdge(
            String label,
            int fromId,
            int toId,
            List<MirVal> args,
            Map<Integer, MirBlock> blockMap) {
        MirBlock target = blockMap.get(toId);
        if (target == null) {
            throw fail(label, "bb" + fromId + " branches to missing block bb" + toId);
        }
        List<MirVal> params = target.params();
        if (args.size() != params.size()) {
            throw fail(
                    label,
                    "bb"
                            + fromId
                            + " -> bb"
                            + toId
                            + " passes "
                            + args.size()
                            + " arg(s) but bb"
                            + toId
                            + " expects "
                            + params.size()
                            + " block parameter(s)");
        }
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).kind() != params.get(i).kind()) {
                throw fail(
                        label,
                        "bb"
                                + fromId
                                + " -> bb"
                                + toId
                                + " arg #"
                                + i
                                + " has kind "
                                + args.get(i).kind()
                                + " but parameter expects "
                                + params.get(i).kind());
            }
        }
    }

    private static IllegalStateException fail(String label, String detail) {
        return new IllegalStateException("MIR verification failed (" + label + ") : " + detail);
    }
}
