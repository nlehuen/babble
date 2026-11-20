package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.lang.Character}.
 */
@Axioms(value = java.lang.Character.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface CharacterAxioms {
    @NonNull
    java.lang.Object toString(char p0);

    @NonNull
    java.lang.Object toString(int p0);

    @NonNull
    java.lang.Object valueOf(char p0);
}
