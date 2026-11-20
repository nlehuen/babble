package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;

/**
 * What the analysis assumes about {@code java.util.function.ToDoubleFunction}.
 */
@Axioms(value = java.util.function.ToDoubleFunction.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface ToDoubleFunctionAxioms {
}
