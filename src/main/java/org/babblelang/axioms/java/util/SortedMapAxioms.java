package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import static org.babblelang.effects.Effect.*;

/**
 * Collection methods of {@code java.util.SortedMap}, as the effect checker assumed them by name.
 *
 * <p>QUESTIONABLE : they hold for every implementation, not only Babble's core collections ; and
 * the mutators are assumed {@link ShallowMut shallow}, although hashed and sorted collections
 * call the elements' {@code hashCode}, {@code equals} or {@code compareTo}.
 */
@Axioms(value = java.util.SortedMap.class)
interface SortedMapAxioms {
    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.util.Comparator comparator();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.lang.Object firstKey();

    @View @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.util.SortedMap headMap(java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.lang.Object lastKey();

    @View @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.util.SortedMap subMap(java.lang.Object p0, java.lang.Object p1);

    @View @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.util.SortedMap tailMap(java.lang.Object p0);
}
