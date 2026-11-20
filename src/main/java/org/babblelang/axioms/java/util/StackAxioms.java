package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.ShallowMut;

import static org.babblelang.effects.Effect.*;

/**
 * Collection methods of {@code java.util.Stack}, as the effect checker assumed them by name.
 *
 * <p>QUESTIONABLE : they hold for every implementation, not only Babble's core collections ; and
 * the mutators are assumed {@link ShallowMut shallow}, although hashed and sorted collections
 * call the elements' {@code hashCode}, {@code equals} or {@code compareTo}.
 */
@Axioms(value = java.util.Stack.class)
interface StackAxioms {
    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.lang.Object peek();
}
