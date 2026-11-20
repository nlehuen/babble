package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.FreshResult;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.ShallowMut;

/**
 * What the analysis assumes about {@code java.util.Set}.
 *
 * <p>QUESTIONABLE : the collection rules hold by name for every implementation, not only Babble's
 * core collections ; the mutators are assumed {@link ShallowMut shallow}, although hashed and
 * sorted collections call their elements' {@code hashCode}, {@code equals} or {@code compareTo} ; and
 * building any collection or iterator counts as only allocating.
 */
@Axioms(value = java.util.Set.class,
        inherited = false)
interface SetAxioms {
    @FreshResult @NonNull
    java.lang.Object copyOf(@NonNull java.util.Collection p0);

    @FreshResult @NonNull
    java.lang.Object of();

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object[] p0);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7, @NonNull java.lang.Object p8, @NonNull java.lang.Object p9);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7, @NonNull java.lang.Object p8);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0);
}
