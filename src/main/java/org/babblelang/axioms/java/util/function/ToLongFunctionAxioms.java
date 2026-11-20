package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.ToLongFunction}.
 */
@Axioms(value = java.util.function.ToLongFunction.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface ToLongFunctionAxioms {
}
