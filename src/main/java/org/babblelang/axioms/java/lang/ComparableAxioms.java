package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;

import static org.babblelang.effects.Effect.*;

/**
 * QUESTIONABLE : {@code compareTo} is assumed pure on every class, although a Babble class may
 * implement it with a {@code mut} parameter ; the sorted collections, which call it, then mutate
 * their elements (BIP019 §1).
 */
@Axioms(Comparable.class)
interface ComparableAxioms {
    @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    int compareTo(Object other);
}
