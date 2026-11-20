package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.FreshResult;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.Collections}.
 */
@Axioms(value = java.util.Collections.class,
        inherited = false)
interface CollectionsAxioms {
    @NonNull
    java.lang.Object emptyEnumeration();

    @NonNull
    java.lang.Object emptyIterator();

    @FreshResult @NonNull
    java.lang.Object emptyList();

    @NonNull
    java.lang.Object emptyListIterator();

    @FreshResult @NonNull
    java.lang.Object emptyMap();

    @NonNull
    java.lang.Object emptyNavigableMap();

    @NonNull
    java.lang.Object emptyNavigableSet();

    @FreshResult @NonNull
    java.lang.Object emptySet();

    @NonNull
    java.lang.Object emptySortedMap();

    @NonNull
    java.lang.Object emptySortedSet();

    @FreshResult @NonNull
    java.lang.Object nCopies(int p0, java.lang.Object p1);

    @NonNull
    java.lang.Object reverseOrder();

    @NonNull
    java.lang.Object reverseOrder(java.util.Comparator p0);

    @FreshResult @NonNull
    java.lang.Object singleton(java.lang.Object p0);

    @FreshResult @NonNull
    java.lang.Object singletonList(java.lang.Object p0);

    @FreshResult @NonNull
    java.lang.Object singletonMap(java.lang.Object p0, java.lang.Object p1);

    @NonNull
    java.lang.Object synchronizedCollection(java.util.Collection p0);

    @NonNull
    java.lang.Object synchronizedList(java.util.List p0);

    @NonNull
    java.lang.Object synchronizedMap(java.util.Map p0);

    @NonNull
    java.lang.Object synchronizedNavigableMap(java.util.NavigableMap p0);

    @NonNull
    java.lang.Object synchronizedNavigableSet(java.util.NavigableSet p0);

    @NonNull
    java.lang.Object synchronizedSet(java.util.Set p0);

    @NonNull
    java.lang.Object synchronizedSortedMap(java.util.SortedMap p0);

    @NonNull
    java.lang.Object synchronizedSortedSet(java.util.SortedSet p0);

    @NonNull
    java.lang.Object unmodifiableCollection(java.util.Collection p0);

    @NonNull
    java.lang.Object unmodifiableList(java.util.List p0);

    @NonNull
    java.lang.Object unmodifiableMap(java.util.Map p0);

    @NonNull
    java.lang.Object unmodifiableNavigableMap(java.util.NavigableMap p0);

    @NonNull
    java.lang.Object unmodifiableNavigableSet(java.util.NavigableSet p0);

    @NonNull
    java.lang.Object unmodifiableSequencedCollection(java.util.SequencedCollection p0);

    @NonNull
    java.lang.Object unmodifiableSequencedMap(java.util.SequencedMap p0);

    @NonNull
    java.lang.Object unmodifiableSequencedSet(java.util.SequencedSet p0);

    @NonNull
    java.lang.Object unmodifiableSet(java.util.Set p0);

    @NonNull
    java.lang.Object unmodifiableSortedMap(java.util.SortedMap p0);

    @NonNull
    java.lang.Object unmodifiableSortedSet(java.util.SortedSet p0);
}
