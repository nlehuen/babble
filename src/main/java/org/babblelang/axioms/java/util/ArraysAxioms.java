package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.FreshResult;
import org.babblelang.axioms.NonNull;

/**
 * QUESTIONABLE : {@code asList} is assumed to return a fresh list, but it is a view of its array.
 */
@Axioms(value = java.util.Arrays.class,
        inherited = false)
interface ArraysAxioms {
    @FreshResult @NonNull
    java.lang.Object asList(java.lang.Object[] p0);

    @NonNull
    java.lang.Object copyOf(byte[] p0, int p1);

    @NonNull
    java.lang.Object copyOf(char[] p0, int p1);

    @NonNull
    java.lang.Object copyOf(double[] p0, int p1);

    @NonNull
    java.lang.Object copyOf(float[] p0, int p1);

    @NonNull
    java.lang.Object copyOf(int[] p0, int p1);

    @NonNull
    java.lang.Object copyOf(long[] p0, int p1);

    @NonNull
    java.lang.Object copyOf(java.lang.Object[] p0, int p1, java.lang.Class p2);

    @NonNull
    java.lang.Object copyOf(java.lang.Object[] p0, int p1);

    @NonNull
    java.lang.Object copyOf(short[] p0, int p1);

    @NonNull
    java.lang.Object copyOf(boolean[] p0, int p1);

    @NonNull
    java.lang.Object copyOfRange(byte[] p0, int p1, int p2);

    @NonNull
    java.lang.Object copyOfRange(char[] p0, int p1, int p2);

    @NonNull
    java.lang.Object copyOfRange(double[] p0, int p1, int p2);

    @NonNull
    java.lang.Object copyOfRange(float[] p0, int p1, int p2);

    @NonNull
    java.lang.Object copyOfRange(int[] p0, int p1, int p2);

    @NonNull
    java.lang.Object copyOfRange(long[] p0, int p1, int p2);

    @NonNull
    java.lang.Object copyOfRange(java.lang.Object[] p0, int p1, int p2, java.lang.Class p3);

    @NonNull
    java.lang.Object copyOfRange(java.lang.Object[] p0, int p1, int p2);

    @NonNull
    java.lang.Object copyOfRange(short[] p0, int p1, int p2);

    @NonNull
    java.lang.Object copyOfRange(boolean[] p0, int p1, int p2);

    @NonNull
    java.lang.Object stream(double[] p0, int p1, int p2);

    @NonNull
    java.lang.Object stream(double[] p0);

    @NonNull
    java.lang.Object stream(int[] p0, int p1, int p2);

    @NonNull
    java.lang.Object stream(int[] p0);

    @NonNull
    java.lang.Object stream(long[] p0, int p1, int p2);

    @NonNull
    java.lang.Object stream(long[] p0);

    @NonNull
    java.lang.Object stream(java.lang.Object[] p0, int p1, int p2);

    @NonNull
    java.lang.Object stream(java.lang.Object[] p0);

    @NonNull
    java.lang.Object toString(byte[] p0);

    @NonNull
    java.lang.Object toString(char[] p0);

    @NonNull
    java.lang.Object toString(double[] p0);

    @NonNull
    java.lang.Object toString(float[] p0);

    @NonNull
    java.lang.Object toString(int[] p0);

    @NonNull
    java.lang.Object toString(long[] p0);

    @NonNull
    java.lang.Object toString(java.lang.Object[] p0);

    @NonNull
    java.lang.Object toString(short[] p0);

    @NonNull
    java.lang.Object toString(boolean[] p0);
}
