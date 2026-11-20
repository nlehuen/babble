package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.FreshResult;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.Nullable;
import org.babblelang.axioms.Parametric;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.util.List}.
 *
 * <p>QUESTIONABLE : the collection rules hold by name for every implementation, not only Babble's
 * core collections ; the mutators are assumed {@link ShallowMut shallow}, although hashed and
 * sorted collections call their elements' {@code hashCode}, {@code equals} or {@code compareTo} ; and
 * building any collection or iterator counts as only allocating.
 */
@Axioms(value = java.util.List.class,
        inherited = false)
interface ListAxioms {
    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object add(int p0, @Parametric java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED, borrowedParameters = true) @ShallowMut
    java.lang.Object addAll(int p0, @NonNull java.util.Collection p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object addFirst(@Parametric java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object addLast(@Parametric java.lang.Object p0);

    @FreshResult @NonNull
    java.lang.Object copyOf(@NonNull java.util.Collection p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @Parametric
    java.lang.Object get(int p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @Parametric
    java.lang.Object getFirst();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @Parametric
    java.lang.Object getLast();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.lang.Object indexOf(@Nullable java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.lang.Object lastIndexOf(@Nullable java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object listIterator();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object listIterator(int p0);

    @FreshResult @NonNull
    java.lang.Object of();

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object[] p0);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7, @NonNull java.lang.Object p8, @NonNull java.lang.Object p9);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7, @NonNull java.lang.Object p8);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut @Parametric
    java.lang.Object remove(int p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut @Parametric
    java.lang.Object removeFirst();

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut @Parametric
    java.lang.Object removeLast();

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object replaceAll(java.util.function.UnaryOperator p0);

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @View @NonNull
    java.lang.Object reversed();

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut @Parametric
    java.lang.Object set(int p0, @Parametric java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object sort(java.util.Comparator p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object subList(int p0, int p1);
}
