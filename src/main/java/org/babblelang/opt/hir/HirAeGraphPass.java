package org.babblelang.opt.hir;

import java.util.List;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.opt.HirEffectAnalysis;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;

/**
 * Dominator-scoped Acyclic E-Graph ({@code HirAeGraphPass}) unifying algebraic simplification,
 * GVN/CSE, alias-partitioned store-to-load forwarding, idempotent call collapsing, and conversion
 * simplification directly on structured {@link HirModule} ({@code BIP039-D6}, {@code BIP039-I4}).
 *
 * <p>Replaces expression-local {@link HirEGraphSimplifyPass} at {@code -O2}.
 */
public final class HirAeGraphPass implements HirPass {
    @Override
    public String name() {
        return "HirAeGraph";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] nextVarId = {module.nextVarId()};
        int[] rewrites = {0};
        HirEffectAnalysis moduleFx = HirEffectAnalysis.forModule(module);

        HirModule updated =
                HirRewriter.rewriteModule(
                        module,
                        (ownerId, fnId, stmts) -> {
                            HirFunction fn = fnId >= 0 ? module.function(fnId) : null;
                            HirEffectAnalysis fx =
                                    fn != null
                                            ? HirEffectAnalysis.forFunction(fn, module)
                                            : moduleFx;
                            AeGraphState state = new AeGraphState(new AeGraphState.Shared());
                            AeGraphBuilder builder =
                                    new AeGraphBuilder(module, fx, ownerId, nextVarId, rewrites);
                            return builder.rewriteBody(stmts, state, true);
                        },
                        expr -> {
                            AeGraphState state = new AeGraphState(new AeGraphState.Shared());
                            AeGraphBuilder builder =
                                    new AeGraphBuilder(module, moduleFx, 0, nextVarId, rewrites);
                            List<HirExpr> r = builder.rewriteBody(List.of(expr), state, true);
                            return r.size() == 1 ? r.get(0) : expr;
                        },
                        () -> nextVarId[0]);

        return updated == module ? Result.unchanged(module) : new Result(updated, rewrites[0]);
    }
}
