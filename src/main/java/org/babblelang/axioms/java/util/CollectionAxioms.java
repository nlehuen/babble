package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import static org.babblelang.effects.Effect.*;

/**
 * Collection methods of {@code java.util.Collection}, as the effect checker assumed them by name.
 *
 * <p>QUESTIONABLE : they hold for every implementation, not only Babble's core collections ; and
 * the mutators are assumed {@link ShallowMut shallow}, although hashed and sorted collections
 * call the elements' {@code hashCode}, {@code equals} or {@code compareTo}.
 */
@Axioms(value = java.util.Collection.class,
        allConstructors = @Profile(value = {HERMETIC, DETERMINISTIC}, receiver = Receiver.SHARED, terminating = false))
interface CollectionAxioms {
    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    boolean add(java.lang.Object p0);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED, borrowedParameters = true)
    boolean addAll(java.util.Collection p0);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    void clear();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    boolean contains(java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    boolean isEmpty();

    @View @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.util.Iterator iterator();

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    boolean remove(java.lang.Object p0);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED, borrowedParameters = true)
    boolean removeAll(java.util.Collection p0);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED, borrowedParameters = true)
    boolean retainAll(java.util.Collection p0);

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    int size();

    @Profile({PURE, HERMETIC, DETERMINISTIC}) int hashCode();

    @Profile({PURE, HERMETIC, DETERMINISTIC}) java.lang.String toString();
}
