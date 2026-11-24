package org.babblelang.ir.mir;

import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirVar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable per-function SSA Control-Flow Graph ({@code BIP014-P3} §6.4).
 *
 * <p>Each {@link MirFunction} corresponds 1-to-1 to a non-namespace-scoped {@link org.babblelang.ir.hir.HirFunction}
 * (identified by {@link #hirFunctionId()}). Parameters that live as non-cell, non-try-escaped
 * values enter as SSA values in {@link #params()} (ordered matching {@code HirFunction.params()},
 * with {@code null} placeholders only for parameters without a frame {@link HirVar}).
 */
public record MirFunction(
        int hirFunctionId,
        String name,
        String ownerClassName,
        int ownerClassId,
        List<MirVal> params,
        List<HirVar> paramVars,
        PrimitiveKind returnKind,
        int bodyWideningRank,
        int entryBlockId,
        List<MirBlock> blocks,
        int nextValId,
        int nextBlockId,
        List<HirVar> pinnedVars,
        Map<Integer, MirEffect.AliasClass> valAliases
) {
    public MirFunction {
        params = List.copyOf(params);
        paramVars = List.copyOf(paramVars);
        returnKind = returnKind != null ? returnKind : PrimitiveKind.NONE;
        blocks = List.copyOf(blocks);
        pinnedVars = List.copyOf(pinnedVars);
        valAliases = Map.copyOf(valAliases);
    }

    public MirBlock block(int blockId) {
        for (MirBlock b : blocks) {
            if (b.id() == blockId) {
                return b;
            }
        }
        return null;
    }

    public Map<Integer, MirBlock> blockMap() {
        Map<Integer, MirBlock> map = new LinkedHashMap<>(blocks.size() * 2);
        for (MirBlock b : blocks) {
            map.put(b.id(), b);
        }
        return map;
    }

    public MirFunction withBlocks(List<MirBlock> newBlocks, int newNextValId, int newNextBlockId) {
        return new MirFunction(hirFunctionId, name, ownerClassName, ownerClassId, params, paramVars,
                returnKind, bodyWideningRank, entryBlockId, newBlocks, newNextValId, newNextBlockId,
                pinnedVars, valAliases);
    }

    public MirFunction withBlocksAndAliases(List<MirBlock> newBlocks, int newNextValId, int newNextBlockId,
                                            Map<Integer, MirEffect.AliasClass> newAliases) {
        return new MirFunction(hirFunctionId, name, ownerClassName, ownerClassId, params, paramVars,
                returnKind, bodyWideningRank, entryBlockId, newBlocks, newNextValId, newNextBlockId,
                pinnedVars, Objects.requireNonNull(newAliases, "newAliases"));
    }
}
