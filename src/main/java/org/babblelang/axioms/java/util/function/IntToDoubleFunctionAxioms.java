package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.IntToDoubleFunction}.
 */
@Axioms(value = java.util.function.IntToDoubleFunction.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface IntToDoubleFunctionAxioms {
}
