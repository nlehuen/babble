package org.babblelang.ir.hir;

import java.util.List;

/**
 * Statically resolved type path ({@code implements}/{@code extends} entry or {@code super} target).
 */
public record HirTypeRef(
        int line,
        String text,
        List<String> path,
        HirVar rootVar
) {
    public HirTypeRef {
        path = List.copyOf(path);
    }
}
