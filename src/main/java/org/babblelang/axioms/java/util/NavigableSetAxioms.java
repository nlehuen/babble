package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import static org.babblelang.effects.Effect.*;

/**
 * Collection methods of {@code java.util.NavigableSet}, as the effect checker assumed them by name.
 *
 * <p>QUESTIONABLE : they hold for every implementation, not only Babble's core collections ; and
 * the mutators are assumed {@link ShallowMut shallow}, although hashed and sorted collections
 * call the elements' {@code hashCode}, {@code equals} or {@code compareTo}.
 */
@Axioms(value = java.util.NavigableSet.class)
interface NavigableSetAxioms {
    @View @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.util.NavigableSet descendingSet();

    @View @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.util.NavigableSet headSet(java.lang.Object p0, boolean p1);

    @View @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.util.NavigableSet subSet(java.lang.Object p0, boolean p1, java.lang.Object p2, boolean p3);

    @View @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.util.NavigableSet tailSet(java.lang.Object p0, boolean p1);
}
