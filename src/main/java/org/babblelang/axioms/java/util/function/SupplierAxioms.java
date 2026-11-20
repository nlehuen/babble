package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.Supplier}.
 */
@Axioms(value = java.util.function.Supplier.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface SupplierAxioms {
}
