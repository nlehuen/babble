package org.babblelang.ir.mir;

import java.util.List;
import java.util.Objects;

/**
 * Immutable basic block in an SSA CFG {@link MirFunction} ({@code BIP014-P3} §6.4).
 *
 * <p>Block parameters ({@link #params()}) replace traditional {@code phi} nodes. Exception-handler
 * entry blocks have {@link #handlerEntry()} {@code == true} and carry the caught {@link Throwable}
 * in {@code params().get(0)}.
 */
public record MirBlock(
        int id,
        List<MirVal> params,
        List<MirInst> insts,
        MirTerminator terminator,
        List<MirHandler> handlers,
        boolean handlerEntry
) {
    public MirBlock {
        params = List.copyOf(params);
        insts = List.copyOf(insts);
        Objects.requireNonNull(terminator, "terminator");
        handlers = List.copyOf(handlers);
    }

    public MirBlock withInsts(List<MirInst> newInsts) {
        return new MirBlock(id, params, newInsts, terminator, handlers, handlerEntry);
    }

    public MirBlock withTerminator(MirTerminator newTerminator) {
        return new MirBlock(id, params, insts, newTerminator, handlers, handlerEntry);
    }

    public MirBlock withInstsAndTerminator(List<MirInst> newInsts, MirTerminator newTerminator) {
        return new MirBlock(id, params, newInsts, newTerminator, handlers, handlerEntry);
    }

    public MirBlock withParams(List<MirVal> newParams) {
        return new MirBlock(id, newParams, insts, terminator, handlers, handlerEntry);
    }

    public MirBlock withHandlers(List<MirHandler> newHandlers) {
        return new MirBlock(id, params, insts, terminator, newHandlers, handlerEntry);
    }
}
