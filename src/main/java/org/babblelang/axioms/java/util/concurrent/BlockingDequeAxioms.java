package org.babblelang.axioms.java.util.concurrent;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;
import org.babblelang.axioms.ShallowMut;

import static org.babblelang.effects.Effect.*;

/**
 * Collection methods of {@code java.util.concurrent.BlockingDeque}, as the effect checker assumed them by name.
 *
 * <p>QUESTIONABLE : they hold for every implementation, not only Babble's core collections ; and
 * the mutators are assumed {@link ShallowMut shallow}, although hashed and sorted collections
 * call the elements' {@code hashCode}, {@code equals} or {@code compareTo}.
 */
@Axioms(value = java.util.concurrent.BlockingDeque.class)
interface BlockingDequeAxioms {
    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    void putFirst(java.lang.Object p0);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    void putLast(java.lang.Object p0);
}
