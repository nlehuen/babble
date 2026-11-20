package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.FreshResult;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import static org.babblelang.effects.Effect.*;

/**
 * Collection methods of {@code java.util.Map}, as the effect checker assumed them by name.
 *
 * <p>QUESTIONABLE : they hold for every implementation, not only Babble's core collections ; and
 * the mutators are assumed {@link ShallowMut shallow}, although hashed and sorted collections
 * call the elements' {@code hashCode}, {@code equals} or {@code compareTo}.
 */
@Axioms(value = java.util.Map.class,
        allConstructors = @Profile(value = {HERMETIC, DETERMINISTIC}, receiver = Receiver.SHARED, terminating = false))
interface MapAxioms {
    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    void clear();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    boolean containsKey(java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    boolean containsValue(java.lang.Object p0);

    @View @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.util.Set entrySet();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.lang.Object get(java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.lang.Object getOrDefault(java.lang.Object p0, java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    boolean isEmpty();

    @View @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.util.Set keySet();

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    java.lang.Object put(java.lang.Object p0, java.lang.Object p1);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED, borrowedParameters = true)
    void putAll(java.util.Map p0);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    java.lang.Object putIfAbsent(java.lang.Object p0, java.lang.Object p1);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    boolean remove(java.lang.Object p0, java.lang.Object p1);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    java.lang.Object remove(java.lang.Object p0);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    boolean replace(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    java.lang.Object replace(java.lang.Object p0, java.lang.Object p1);

    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    void replaceAll(java.util.function.BiFunction p0);

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    int size();

    @View @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.util.Collection values();

    @Profile({PURE, HERMETIC, DETERMINISTIC}) int hashCode();

    @Profile({PURE, HERMETIC, DETERMINISTIC}) java.lang.String toString();

    @FreshResult java.util.Map copyOf(java.util.Map p0);

    @FreshResult java.util.Map of();

    @FreshResult java.util.Map of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7, java.lang.Object p8, java.lang.Object p9, java.lang.Object p10, java.lang.Object p11, java.lang.Object p12, java.lang.Object p13, java.lang.Object p14, java.lang.Object p15, java.lang.Object p16, java.lang.Object p17, java.lang.Object p18, java.lang.Object p19);

    @FreshResult java.util.Map of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7, java.lang.Object p8, java.lang.Object p9, java.lang.Object p10, java.lang.Object p11, java.lang.Object p12, java.lang.Object p13, java.lang.Object p14, java.lang.Object p15, java.lang.Object p16, java.lang.Object p17);

    @FreshResult java.util.Map of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7, java.lang.Object p8, java.lang.Object p9, java.lang.Object p10, java.lang.Object p11, java.lang.Object p12, java.lang.Object p13, java.lang.Object p14, java.lang.Object p15);

    @FreshResult java.util.Map of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7, java.lang.Object p8, java.lang.Object p9, java.lang.Object p10, java.lang.Object p11, java.lang.Object p12, java.lang.Object p13);

    @FreshResult java.util.Map of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7, java.lang.Object p8, java.lang.Object p9, java.lang.Object p10, java.lang.Object p11);

    @FreshResult java.util.Map of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7, java.lang.Object p8, java.lang.Object p9);

    @FreshResult java.util.Map of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5, java.lang.Object p6, java.lang.Object p7);

    @FreshResult java.util.Map of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3, java.lang.Object p4, java.lang.Object p5);

    @FreshResult java.util.Map of(java.lang.Object p0, java.lang.Object p1, java.lang.Object p2, java.lang.Object p3);

    @FreshResult java.util.Map of(java.lang.Object p0, java.lang.Object p1);
}
