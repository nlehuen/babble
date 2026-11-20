package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.FreshResult;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import static org.babblelang.effects.Effect.*;

/**
 * Collection methods of {@code java.util.List}, as the effect checker assumed them by name.
 *
 * <p>QUESTIONABLE : they hold for every implementation, not only Babble's core collections ; and
 * the mutators are assumed {@link ShallowMut shallow}, although hashed and sorted collections
 * call the elements' {@code hashCode}, {@code equals} or {@code compareTo}.
 */
@Axioms(value = java.util.List.class)
interface ListAxioms {
    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    void add(int p0, java.lang.Object p1);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED, borrowedParameters = true)
    boolean addAll(int p0, java.util.Collection p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.lang.Object get(int p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    int indexOf(java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    int lastIndexOf(java.lang.Object p0);

    @View @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.util.ListIterator listIterator();

    @View @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.util.ListIterator listIterator(int p0);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    java.lang.Object remove(int p0);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    void replaceAll(java.util.function.UnaryOperator p0);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    java.lang.Object set(int p0, java.lang.Object p1);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    void sort(java.util.Comparator p0);

    @View @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.util.List subList(int p0, int p1);

    @FreshResult java.util.List copyOf(java.util.Collection p0);

    @FreshResult java.util.List of();

    @FreshResult java.util.List of(java.lang.Object[] p0);

    @FreshResult java.util.List of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7, java.lang.Object p8, java.lang.Object p9);

    @FreshResult java.util.List of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7, java.lang.Object p8);

    @FreshResult java.util.List of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7);

    @FreshResult java.util.List of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6);

    @FreshResult java.util.List of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5);

    @FreshResult java.util.List of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4);

    @FreshResult java.util.List of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3);

    @FreshResult java.util.List of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2);

    @FreshResult java.util.List of(java.lang.Object p0, java.lang.Object p1);

    @FreshResult java.util.List of(java.lang.Object p0);
}
