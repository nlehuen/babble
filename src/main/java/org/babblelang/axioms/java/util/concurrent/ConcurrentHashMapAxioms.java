package org.babblelang.axioms.java.util.concurrent;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import static org.babblelang.effects.Effect.*;

/**
 * Collection methods of {@code java.util.concurrent.ConcurrentHashMap}, as the effect checker assumed them by name.
 *
 * <p>QUESTIONABLE : they hold for every implementation, not only Babble's core collections ; and
 * the mutators are assumed {@link ShallowMut shallow}, although hashed and sorted collections
 * call the elements' {@code hashCode}, {@code equals} or {@code compareTo}.
 */
@Axioms(value = java.util.concurrent.ConcurrentHashMap.class)
interface ConcurrentHashMapAxioms {
    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    boolean contains(java.lang.Object p0);

    @View @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.util.concurrent.ConcurrentHashMap.KeySetView keySet(java.lang.Object p0);
}
