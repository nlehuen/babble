package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.util.AbstractMap.SimpleImmutableEntry}.
 */
@Axioms(value = java.util.AbstractMap.SimpleImmutableEntry.class,
        inherited = false,
        allConstructors = @Profile(value = {HERMETIC, DETERMINISTIC}, terminating = false, receiver = Receiver.SHARED))
interface AbstractMapSimpleImmutableEntryAxioms {
}
