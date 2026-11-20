package org.babblelang.axioms.java.util.concurrent;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.ShallowMut;

import static org.babblelang.effects.Effect.*;

/**
 * Collection methods of {@code java.util.concurrent.CopyOnWriteArrayList}, as the effect checker assumed them by name.
 *
 * <p>QUESTIONABLE : they hold for every implementation, not only Babble's core collections ; and
 * the mutators are assumed {@link ShallowMut shallow}, although hashed and sorted collections
 * call the elements' {@code hashCode}, {@code equals} or {@code compareTo}.
 */
@Axioms(value = java.util.concurrent.CopyOnWriteArrayList.class)
interface CopyOnWriteArrayListAxioms {
    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    int indexOf(java.lang.Object p0, int p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    int lastIndexOf(java.lang.Object p0, int p1);
}
