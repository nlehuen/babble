package org.babblelang.ir.hir;

/** Call argument (positional when {@link #name()} is {@code null}, named otherwise). */
public record HirCallArg(String name, HirExpr value) {
    public HirCallArg withValue(HirExpr newValue) {
        return newValue == value ? this : new HirCallArg(name, newValue);
    }
}
