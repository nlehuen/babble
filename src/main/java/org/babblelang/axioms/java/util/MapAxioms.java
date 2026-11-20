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
 * What the analysis assumes about {@code java.util.Map}.
 *
 * <p>QUESTIONABLE : the collection rules hold by name for every implementation, not only Babble's
 * core collections ; the mutators are assumed {@link ShallowMut shallow}, although hashed and
 * sorted collections call their elements' {@code hashCode}, {@code equals} or {@code compareTo} ; and
 * building any collection or iterator counts as only allocating.
 */
@Axioms(value = java.util.Map.class,
        nullMarked = Axioms.NullMarked.SUBTYPES,
        allConstructors = @Profile(value = {HERMETIC, DETERMINISTIC}, terminating = false, receiver = Receiver.SHARED))
interface MapAxioms {
    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object clear();

    @Nullable
    java.lang.Object compute(java.lang.Object p0, java.util.function.BiFunction p1);

    @Nullable
    java.lang.Object computeIfAbsent(java.lang.Object p0, java.util.function.Function p1);

    @Nullable
    java.lang.Object computeIfPresent(java.lang.Object p0, java.util.function.BiFunction p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.lang.Object containsKey(@Nullable java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true)
    java.lang.Object containsValue(@Nullable java.lang.Object p0);

    @FreshResult @NonNull
    java.lang.Object copyOf(@NonNull java.util.Map p0);

    @NonNull
    java.lang.Object entry(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @View @NonNull
    java.lang.Object entrySet();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @Nullable
    java.lang.Object get(@Nullable java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @Parametric
    java.lang.Object getOrDefault(@Nullable java.lang.Object p0, @Parametric java.lang.Object p1);

    @Profile({PURE, HERMETIC, DETERMINISTIC})
    int hashCode();

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.lang.Object isEmpty();

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @View @NonNull
    java.lang.Object keySet();

    @Nullable
    java.lang.Object merge(java.lang.Object p0, java.lang.Object p1, java.util.function.BiFunction p2);

    @FreshResult @NonNull
    java.lang.Object of();

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7, @NonNull java.lang.Object p8, @NonNull java.lang.Object p9, @NonNull java.lang.Object p10, @NonNull java.lang.Object p11, @NonNull java.lang.Object p12, @NonNull java.lang.Object p13, @NonNull java.lang.Object p14, @NonNull java.lang.Object p15, @NonNull java.lang.Object p16, @NonNull java.lang.Object p17, @NonNull java.lang.Object p18, @NonNull java.lang.Object p19);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7, @NonNull java.lang.Object p8, @NonNull java.lang.Object p9, @NonNull java.lang.Object p10, @NonNull java.lang.Object p11, @NonNull java.lang.Object p12, @NonNull java.lang.Object p13, @NonNull java.lang.Object p14, @NonNull java.lang.Object p15, @NonNull java.lang.Object p16, @NonNull java.lang.Object p17);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7, @NonNull java.lang.Object p8, @NonNull java.lang.Object p9, @NonNull java.lang.Object p10, @NonNull java.lang.Object p11, @NonNull java.lang.Object p12, @NonNull java.lang.Object p13, @NonNull java.lang.Object p14, @NonNull java.lang.Object p15);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7, @NonNull java.lang.Object p8, @NonNull java.lang.Object p9, @NonNull java.lang.Object p10, @NonNull java.lang.Object p11, @NonNull java.lang.Object p12, @NonNull java.lang.Object p13);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7, @NonNull java.lang.Object p8, @NonNull java.lang.Object p9, @NonNull java.lang.Object p10, @NonNull java.lang.Object p11);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7, @NonNull java.lang.Object p8, @NonNull java.lang.Object p9);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5, @NonNull java.lang.Object p6, @NonNull java.lang.Object p7);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3, @NonNull java.lang.Object p4, @NonNull java.lang.Object p5);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1, @NonNull java.lang.Object p2, @NonNull java.lang.Object p3);

    @FreshResult @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0, @NonNull java.lang.Object p1);

    @NonNull
    java.lang.Object ofEntries(@NonNull java.util.Map.Entry[] p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut @Nullable
    java.lang.Object put(@Parametric java.lang.Object p0, @Parametric java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED, borrowedParameters = true) @ShallowMut
    java.lang.Object putAll(@NonNull java.util.Map p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut @Nullable
    java.lang.Object putIfAbsent(@Parametric java.lang.Object p0, @Parametric java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object remove(@Nullable java.lang.Object p0, @Nullable java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut @Nullable
    java.lang.Object remove(@Nullable java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object replace(@Parametric java.lang.Object p0, @Parametric java.lang.Object p1, @Parametric java.lang.Object p2);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut @Nullable
    java.lang.Object replace(@Parametric java.lang.Object p0, @Parametric java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true, receiver = Receiver.SHARED) @ShallowMut
    java.lang.Object replaceAll(java.util.function.BiFunction p0);

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.lang.Object size();

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @View @NonNull
    java.lang.Object values();
}
