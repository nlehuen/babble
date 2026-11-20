package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Parametric;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.util.Map.Entry}.
 */
@Axioms(value = java.util.Map.Entry.class,
        nullMarked = Axioms.NullMarked.SUBTYPES)
interface MapEntryAxioms {
    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, nothrow = true) @Parametric
    java.lang.Object getKey();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, nothrow = true) @Parametric
    java.lang.Object getValue();

    @Profile({PURE, HERMETIC, DETERMINISTIC})
    int hashCode();

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @Parametric
    java.lang.Object setValue(java.lang.Object p0);
}
