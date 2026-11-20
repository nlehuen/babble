package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.lang.Iterable}.
 *
 * <p>QUESTIONABLE : the collection rules hold by name for every implementation, not only Babble's
 * core collections ; the mutators are assumed {@link ShallowMut shallow}, although hashed and
 * sorted collections call their elements' {@code hashCode}, {@code equals} or {@code compareTo} ; and
 * building any collection or iterator counts as only allocating.
 */
@Axioms(value = java.lang.Iterable.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.SUBTYPES,
        allConstructors = @Profile(value = {HERMETIC, DETERMINISTIC}, terminating = false, receiver = Receiver.SHARED))
interface IterableAxioms {
    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @View @NonNull
    java.lang.Object iterator();

    @NonNull
    java.lang.Object spliterator();
}
