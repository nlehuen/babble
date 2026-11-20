package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.ShallowMut;

/**
 * What the analysis assumes about {@code java.util.EnumSet}.
 *
 * <p>QUESTIONABLE : the collection rules hold by name for every implementation, not only Babble's
 * core collections ; the mutators are assumed {@link ShallowMut shallow}, although hashed and
 * sorted collections call their elements' {@code hashCode}, {@code equals} or {@code compareTo} ; and
 * building any collection or iterator counts as only allocating.
 */
@Axioms(value = java.util.EnumSet.class,
        inherited = false)
interface EnumSetAxioms {
    @NonNull
    java.lang.Object copyOf(@NonNull java.util.EnumSet p0);

    @NonNull
    java.lang.Object copyOf(@NonNull java.util.Collection p0);

    @NonNull
    java.lang.Object of(@NonNull java.lang.Enum p0, @NonNull java.lang.Enum[] p1);

    @NonNull
    java.lang.Object of(@NonNull java.lang.Enum p0, @NonNull java.lang.Enum p1, @NonNull java.lang.Enum p2, @NonNull java.lang.Enum p3, @NonNull java.lang.Enum p4);

    @NonNull
    java.lang.Object of(@NonNull java.lang.Enum p0, @NonNull java.lang.Enum p1, @NonNull java.lang.Enum p2, @NonNull java.lang.Enum p3);

    @NonNull
    java.lang.Object of(@NonNull java.lang.Enum p0, @NonNull java.lang.Enum p1, @NonNull java.lang.Enum p2);

    @NonNull
    java.lang.Object of(@NonNull java.lang.Enum p0, @NonNull java.lang.Enum p1);

    @NonNull
    java.lang.Object of(@NonNull java.lang.Enum p0);
}
