package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.Function}.
 */
@Axioms(value = java.util.function.Function.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface FunctionAxioms {
    @NonNull
    java.lang.Object andThen(java.util.function.Function p0);

    @NonNull
    java.lang.Object compose(java.util.function.Function p0);

    @NonNull
    java.lang.Object identity();
}
