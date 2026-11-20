package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.DoubleSupplier}.
 */
@Axioms(value = java.util.function.DoubleSupplier.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface DoubleSupplierAxioms {
}
