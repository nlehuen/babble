package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.StringTokenizer}.
 */
@Axioms(value = java.util.StringTokenizer.class,
        inherited = false)
interface StringTokenizerAxioms {
    @NonNull
    java.lang.Object nextToken();

    @NonNull
    java.lang.Object nextToken(java.lang.String p0);
}
