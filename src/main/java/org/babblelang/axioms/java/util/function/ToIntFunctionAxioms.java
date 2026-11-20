package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.ToIntFunction}.
 */
@Axioms(value = java.util.function.ToIntFunction.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface ToIntFunctionAxioms {
}
