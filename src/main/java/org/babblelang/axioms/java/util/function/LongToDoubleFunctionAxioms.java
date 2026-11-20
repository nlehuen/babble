package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.LongToDoubleFunction}.
 */
@Axioms(value = java.util.function.LongToDoubleFunction.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface LongToDoubleFunctionAxioms {
}
