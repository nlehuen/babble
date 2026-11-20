package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Nullable;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.ShallowMut;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.util.PriorityQueue}.
 *
 * <p>QUESTIONABLE : the collection rules hold by name for every implementation, not only Babble's
 * core collections ; the mutators are assumed {@link ShallowMut shallow}, although hashed and
 * sorted collections call their elements' {@code hashCode}, {@code equals} or {@code compareTo} ; and
 * building any collection or iterator counts as only allocating.
 */
@Axioms(value = java.util.PriorityQueue.class,
        inherited = false)
interface PriorityQueueAxioms {
    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @Nullable
    java.lang.Object comparator();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.lang.Object peek();
}
