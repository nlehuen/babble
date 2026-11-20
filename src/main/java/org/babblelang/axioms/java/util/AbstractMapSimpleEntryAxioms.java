package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;

import static org.babblelang.effects.Effect.*;

/** Building a {@code java.util.AbstractMap.SimpleEntry} has no effect but allocation. */
@Axioms(value = java.util.AbstractMap.SimpleEntry.class, inherited = false,
        allConstructors = @Profile(value = {HERMETIC, DETERMINISTIC}, receiver = Receiver.SHARED, terminating = false))
interface AbstractMapSimpleEntryAxioms {
}
