package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;
import org.babblelang.axioms.ShallowMut;

import static org.babblelang.effects.Effect.*;

/**
 * Collection methods of {@code java.util.Iterator}, as the effect checker assumed them by name.
 *
 * <p>QUESTIONABLE : they hold for every implementation, not only Babble's core collections ; and
 * the mutators are assumed {@link ShallowMut shallow}, although hashed and sorted collections
 * call the elements' {@code hashCode}, {@code equals} or {@code compareTo}.
 */
@Axioms(value = java.util.Iterator.class,
        allConstructors = @Profile(value = {HERMETIC, DETERMINISTIC}, receiver = Receiver.SHARED, terminating = false))
interface IteratorAxioms {
    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    boolean hasNext();

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    void remove();

    @Profile({PURE, HERMETIC, DETERMINISTIC}) int hashCode();

    @Profile({PURE, HERMETIC, DETERMINISTIC}) java.lang.String toString();
}
