package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;

import static org.babblelang.effects.Effect.*;

/** The accessors of a map entry, of every implementation. */
@Axioms(java.util.Map.Entry.class)
interface MapEntryAxioms {
    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, nothrow = true)
    Object getKey();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, nothrow = true)
    Object getValue();

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    Object setValue(Object value);

    @Profile({PURE, HERMETIC, DETERMINISTIC})
    int hashCode();

    @Profile({PURE, HERMETIC, DETERMINISTIC})
    String toString();
}
