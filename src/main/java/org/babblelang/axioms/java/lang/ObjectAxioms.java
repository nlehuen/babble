package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.MethodName;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.Nullable;
import org.babblelang.axioms.Profile;

import static org.babblelang.effects.Effect.*;

/**
 * The value protocol of every class.
 *
 * <p>QUESTIONABLE : {@code equals}, {@code hashCode} and {@code toString} are assumed pure on
 * every class, although any class, a Babble class included, may override them with effects ;
 * and an override is assumed deterministic.
 */
@Axioms(value = java.lang.Object.class,
        nullMarked = Axioms.NullMarked.TARGET)
interface ObjectAxioms {
    @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    boolean equals(@Nullable java.lang.Object p0);

    @NonNull @MethodName("getClass")
    java.lang.Object getClass$();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, deterministicIfOverridden = true)
    int hashCode();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, deterministicIfOverridden = true) @NonNull
    java.lang.String toString();
}
