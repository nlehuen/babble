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
 * What the analysis assumes about {@code java.util.Collection}.
 *
 * <p>QUESTIONABLE : the collection rules hold by name for every implementation, not only Babble's
 * core collections ; the mutators are assumed {@link ShallowMut shallow}, although hashed and
 * sorted collections call their elements' {@code hashCode}, {@code equals} or {@code compareTo} ; and
 * building any collection or iterator counts as only allocating.
 */
@Axioms(value = java.util.Collection.class,
        allConstructors = @Profile(value = {HERMETIC, DETERMINISTIC}, terminating = false, receiver = Receiver.SHARED))
interface CollectionAxioms {
    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object add(@Parametric java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED, borrowedParameters = true) @ShallowMut
    java.lang.Object addAll(@NonNull java.util.Collection p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object clear();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.lang.Object contains(@Nullable java.lang.Object p0);

    @Profile({PURE, HERMETIC, DETERMINISTIC})
    int hashCode();

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.lang.Object isEmpty();

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @View @NonNull
    java.lang.Object iterator();

    @NonNull
    java.lang.Object parallelStream();

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object remove(@Nullable java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED, borrowedParameters = true) @ShallowMut
    java.lang.Object removeAll(@NonNull java.util.Collection p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED, borrowedParameters = true) @ShallowMut
    java.lang.Object retainAll(@NonNull java.util.Collection p0);

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.lang.Object size();

    @NonNull
    java.lang.Object spliterator();

    @NonNull
    java.lang.Object stream();

    @NonNull
    java.lang.Object toArray();

    @NonNull
    java.lang.Object toArray(java.lang.Object[] p0);

    @NonNull
    java.lang.Object toArray(java.util.function.IntFunction p0);
}
