package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.IntToLongFunction}.
 */
@Axioms(value = java.util.function.IntToLongFunction.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface IntToLongFunctionAxioms {
}
