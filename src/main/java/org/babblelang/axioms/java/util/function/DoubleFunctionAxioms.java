package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.DoubleFunction}.
 */
@Axioms(value = java.util.function.DoubleFunction.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface DoubleFunctionAxioms {
}
