package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.Nullable;
import org.babblelang.axioms.Parametric;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.util.NavigableMap}.
 *
 * <p>QUESTIONABLE : the collection rules hold by name for every implementation, not only Babble's
 * core collections ; the mutators are assumed {@link ShallowMut shallow}, although hashed and
 * sorted collections call their elements' {@code hashCode}, {@code equals} or {@code compareTo} ; and
 * building any collection or iterator counts as only allocating.
 */
@Axioms(value = java.util.NavigableMap.class,
        inherited = false)
interface NavigableMapAxioms {
    @Nullable
    java.lang.Object ceilingEntry(java.lang.Object p0);

    @Nullable
    java.lang.Object ceilingKey(java.lang.Object p0);

    @NonNull
    java.lang.Object descendingKeySet();

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @View @NonNull
    java.lang.Object descendingMap();

    @Nullable
    java.lang.Object firstEntry();

    @Nullable
    java.lang.Object floorEntry(java.lang.Object p0);

    @Nullable
    java.lang.Object floorKey(java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object headMap(@Parametric java.lang.Object p0, boolean p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object headMap(@Parametric java.lang.Object p0);

    @Nullable
    java.lang.Object higherEntry(java.lang.Object p0);

    @Nullable
    java.lang.Object higherKey(java.lang.Object p0);

    @Nullable
    java.lang.Object lastEntry();

    @Nullable
    java.lang.Object lowerEntry(java.lang.Object p0);

    @Nullable
    java.lang.Object lowerKey(java.lang.Object p0);

    @NonNull
    java.lang.Object navigableKeySet();

    @Nullable
    java.lang.Object pollFirstEntry();

    @Nullable
    java.lang.Object pollLastEntry();

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @View @NonNull
    java.lang.Object reversed();

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object subMap(@Parametric java.lang.Object p0, boolean p1, @Parametric java.lang.Object p2, boolean p3);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object subMap(@Parametric java.lang.Object p0, @Parametric java.lang.Object p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object tailMap(@Parametric java.lang.Object p0, boolean p1);

    @Profile(value = {HERMETIC, DETERMINISTIC}, borrowedParameters = true) @View @NonNull
    java.lang.Object tailMap(@Parametric java.lang.Object p0);
}
