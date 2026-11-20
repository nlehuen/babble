package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.FreshResult;
import org.babblelang.axioms.ShallowMut;

/**
 * Collection methods of {@code java.util.Set}, as the effect checker assumed them by name.
 *
 * <p>QUESTIONABLE : they hold for every implementation, not only Babble's core collections ; and
 * the mutators are assumed {@link ShallowMut shallow}, although hashed and sorted collections
 * call the elements' {@code hashCode}, {@code equals} or {@code compareTo}.
 */
@Axioms(value = java.util.Set.class)
interface SetAxioms {
    @FreshResult java.util.Set copyOf(java.util.Collection p0);

    @FreshResult java.util.Set of();

    @FreshResult java.util.Set of(java.lang.Object[] p0);

    @FreshResult java.util.Set of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7, java.lang.Object p8, java.lang.Object p9);

    @FreshResult java.util.Set of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7, java.lang.Object p8);

    @FreshResult java.util.Set of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7);

    @FreshResult java.util.Set of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6);

    @FreshResult java.util.Set of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5);

    @FreshResult java.util.Set of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4);

    @FreshResult java.util.Set of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3);

    @FreshResult java.util.Set of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2);

    @FreshResult java.util.Set of(java.lang.Object p0, java.lang.Object p1);

    @FreshResult java.util.Set of(java.lang.Object p0);
}
