package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;

import static org.babblelang.effects.Effect.*;

/**
 * The value protocol of every class.
 *
 * <p>QUESTIONABLE : {@code equals}, {@code hashCode} and {@code toString} are assumed pure on
 * every class, although any class, a Babble class included, may override them with effects ;
 * and an override is assumed deterministic.
 */
@Axioms(Object.class)
interface ObjectAxioms {
    @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    boolean equals(Object other);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, deterministicIfOverridden = true)
    int hashCode();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, deterministicIfOverridden = true)
    String toString();
}
