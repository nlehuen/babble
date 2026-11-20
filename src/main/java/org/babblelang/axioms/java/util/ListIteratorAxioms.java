package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;
import org.babblelang.axioms.ShallowMut;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.util.ListIterator}.
 *
 * <p>QUESTIONABLE : the collection rules hold by name for every implementation, not only Babble's
 * core collections ; the mutators are assumed {@link ShallowMut shallow}, although hashed and
 * sorted collections call their elements' {@code hashCode}, {@code equals} or {@code compareTo} ; and
 * building any collection or iterator counts as only allocating.
 */
@Axioms(value = java.util.ListIterator.class,
        inherited = false)
interface ListIteratorAxioms {
    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object add(java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.lang.Object hasPrevious();

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object set(java.lang.Object p0);
}
