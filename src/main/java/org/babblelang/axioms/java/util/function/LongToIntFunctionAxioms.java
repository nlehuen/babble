package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.LongToIntFunction}.
 */
@Axioms(value = java.util.function.LongToIntFunction.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface LongToIntFunctionAxioms {
}
