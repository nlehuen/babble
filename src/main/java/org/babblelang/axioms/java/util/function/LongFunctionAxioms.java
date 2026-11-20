package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.LongFunction}.
 */
@Axioms(value = java.util.function.LongFunction.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface LongFunctionAxioms {
}
