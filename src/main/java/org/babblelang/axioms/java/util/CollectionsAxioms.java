package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.FreshResult;

/**
 * Static factories assumed to return a fresh collection.
 */
@Axioms(value = java.util.Collections.class, inherited = false)
interface CollectionsAxioms {
    @FreshResult java.util.List emptyList();

    @FreshResult java.util.Map emptyMap();

    @FreshResult java.util.Set emptySet();

    @FreshResult java.util.List nCopies(int p0, java.lang.Object p1);

    @FreshResult java.util.Set singleton(java.lang.Object p0);

    @FreshResult java.util.List singletonList(java.lang.Object p0);

    @FreshResult java.util.Map singletonMap(java.lang.Object p0, java.lang.Object p1);
}
