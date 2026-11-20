package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.IntSupplier}.
 */
@Axioms(value = java.util.function.IntSupplier.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface IntSupplierAxioms {
}
