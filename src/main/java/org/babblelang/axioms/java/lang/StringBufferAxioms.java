package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Constructor;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.lang.StringBuffer}.
 */
@Axioms(value = java.lang.StringBuffer.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface StringBufferAxioms {
    @NonNull
    java.lang.Object append(boolean p0);

    @NonNull
    java.lang.Object append(char p0);

    @NonNull
    java.lang.Object append(char[] p0, int p1, int p2);

    @NonNull
    java.lang.Object append(char[] p0);

    @NonNull
    java.lang.Object append(java.lang.Object p0);

    @NonNull
    java.lang.Object append(double p0);

    @NonNull
    java.lang.Object append(float p0);

    @NonNull
    java.lang.Object append(int p0);

    @NonNull
    java.lang.Object append(java.lang.CharSequence p0, int p1, int p2);

    @NonNull
    java.lang.Object append(long p0);

    @NonNull
    java.lang.Object appendCodePoint(int p0);

    @NonNull
    java.lang.Object delete(int p0, int p1);

    @NonNull
    java.lang.Object deleteCharAt(int p0);

    @NonNull
    java.lang.Object insert(int p0, boolean p1);

    @NonNull
    java.lang.Object insert(int p0, char p1);

    @NonNull
    java.lang.Object insert(int p0, char[] p1, int p2, int p3);

    @NonNull
    java.lang.Object insert(int p0, char[] p1);

    @NonNull
    java.lang.Object insert(int p0, java.lang.Object p1);

    @NonNull
    java.lang.Object insert(int p0, double p1);

    @NonNull
    java.lang.Object insert(int p0, float p1);

    @NonNull
    java.lang.Object insert(int p0, int p1);

    @NonNull
    java.lang.Object insert(int p0, java.lang.CharSequence p1, int p2, int p3);

    @NonNull
    java.lang.Object insert(int p0, long p1);

    @NonNull
    java.lang.Object repeat(int p0, int p1);

    @NonNull
    java.lang.Object repeat(java.lang.CharSequence p0, int p1);

    @NonNull
    java.lang.Object replace(int p0, int p1, java.lang.String p2);

    @NonNull
    java.lang.Object reverse();

    @NonNull
    java.lang.Object subSequence(int p0, int p1);

    @NonNull
    java.lang.Object substring(int p0, int p1);

    @NonNull
    java.lang.Object substring(int p0);

    @Constructor
    void constructor(@NonNull java.lang.String p0);
}
