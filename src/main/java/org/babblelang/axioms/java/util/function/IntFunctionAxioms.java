package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.IntFunction}.
 */
@Axioms(value = java.util.function.IntFunction.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface IntFunctionAxioms {
}
