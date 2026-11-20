package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.LongSupplier}.
 */
@Axioms(value = java.util.function.LongSupplier.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface LongSupplierAxioms {
}
