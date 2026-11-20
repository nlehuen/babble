package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.Nullable;
import org.babblelang.axioms.Parametric;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.util.SortedMap}.
 *
 * <p>QUESTIONABLE : the collection rules hold by name for every implementation, not only Babble's
 * core collections ; the mutators are assumed {@link ShallowMut shallow}, although hashed and
 * sorted collections call their elements' {@code hashCode}, {@code equals} or {@code compareTo} ; and
 * building any collection or iterator counts as only allocating.
 */
@Axioms(value = java.util.SortedMap.class,
        inherited = false)
interface SortedMapAxioms {
    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @Nullable
    java.lang.Object comparator();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @Parametric
    java.lang.Object firstKey();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object headMap(@Parametric java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @Parametric
    java.lang.Object lastKey();

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object putFirst(java.lang.Object p0, java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object putLast(java.lang.Object p0, java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @View @NonNull
    java.lang.Object reversed();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object subMap(@Parametric java.lang.Object p0, @Parametric java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object tailMap(@Parametric java.lang.Object p0);
}
