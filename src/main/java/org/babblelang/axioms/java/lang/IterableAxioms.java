package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import static org.babblelang.effects.Effect.*;

/**
 * Collection methods of {@code java.lang.Iterable}, as the effect checker assumed them by name.
 *
 * <p>QUESTIONABLE : they hold for every implementation, not only Babble's core collections ; and
 * the mutators are assumed {@link ShallowMut shallow}, although hashed and sorted collections
 * call the elements' {@code hashCode}, {@code equals} or {@code compareTo}.
 */
@Axioms(value = java.lang.Iterable.class, inherited = false,
        allConstructors = @Profile(value = {HERMETIC, DETERMINISTIC}, receiver = Receiver.SHARED, terminating = false))
interface IterableAxioms {
    @View @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.util.Iterator iterator();

    @Profile({PURE, HERMETIC, DETERMINISTIC}) int hashCode();

    @Profile({PURE, HERMETIC, DETERMINISTIC}) java.lang.String toString();
}
