package org.babblelang.axioms.java.util.concurrent;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.Parametric;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.util.concurrent.ConcurrentNavigableMap}.
 *
 * <p>QUESTIONABLE : the collection rules hold by name for every implementation, not only Babble's
 * core collections ; the mutators are assumed {@link ShallowMut shallow}, although hashed and
 * sorted collections call their elements' {@code hashCode}, {@code equals} or {@code compareTo} ; and
 * building any collection or iterator counts as only allocating.
 */
@Axioms(value = java.util.concurrent.ConcurrentNavigableMap.class,
        inherited = false)
interface ConcurrentNavigableMapAxioms {
    @NonNull
    java.lang.Object descendingKeySet();

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @View @NonNull
    java.lang.Object descendingMap();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object headMap(@Parametric java.lang.Object p0, boolean p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object headMap(@Parametric java.lang.Object p0);

    @NonNull
    java.lang.Object navigableKeySet();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object subMap(@Parametric java.lang.Object p0, boolean p1, @Parametric java.lang.Object p2, boolean p3);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object subMap(@Parametric java.lang.Object p0, @Parametric java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object tailMap(@Parametric java.lang.Object p0, boolean p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object tailMap(@Parametric java.lang.Object p0);
}
