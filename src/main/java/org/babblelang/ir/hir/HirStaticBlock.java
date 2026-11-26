package org.babblelang.ir.hir;

import java.util.List;

/** Static initializer block attached to a {@link HirClass} or {@link HirInterface}. */
public record HirStaticBlock(
        boolean namespaceScope, List<HirVar> declaredVars, List<HirExpr> body) {
    public HirStaticBlock {
        declaredVars = List.copyOf(declaredVars);
        body = List.copyOf(body);
    }

    public HirStaticBlock withBody(List<HirExpr> newBody) {
        return new HirStaticBlock(namespaceScope, declaredVars, newBody);
    }
}
