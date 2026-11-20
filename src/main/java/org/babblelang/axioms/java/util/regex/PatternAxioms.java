package org.babblelang.axioms.java.util.regex;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.regex.Pattern}.
 */
@Axioms(value = java.util.regex.Pattern.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface PatternAxioms {
    @NonNull
    java.lang.Object compile(java.lang.String p0, int p1);

    @NonNull
    java.lang.Object compile(java.lang.String p0);

    @NonNull
    java.lang.Object matcher(java.lang.CharSequence p0);

    @NonNull
    java.lang.Object pattern();

    @NonNull
    java.lang.Object split(java.lang.CharSequence p0, int p1);

    @NonNull
    java.lang.Object split(java.lang.CharSequence p0);
}
