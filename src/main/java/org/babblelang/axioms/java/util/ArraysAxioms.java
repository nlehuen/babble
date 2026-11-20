package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.FreshResult;

/**
 * Static factories assumed to return a fresh collection.
 *
 * <p>QUESTIONABLE : {@code asList} returns a view of its array, not a fresh list.
 */
@Axioms(value = java.util.Arrays.class, inherited = false)
interface ArraysAxioms {
    @FreshResult java.util.List asList(java.lang.Object[] p0);
}
