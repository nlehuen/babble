package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;
import org.babblelang.axioms.Foldable;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;
import org.babblelang.effects.Idempotent;

import static org.babblelang.effects.Effect.*;

/**
 * Every method of {@code String} is pure, deterministic and borrows its receiver and
 * arguments ; those with their own {@link Profile} are also {@code nothrow}. What the bytecode
 * analyzer assumes ({@link BytecodeSeed}) and what the optimizer may call at compile time
 * ({@link Foldable}) are stated separately : the analyzer counts {@code valueOf} as {@code nothrow},
 * the effect checker does not.
 */
@Axioms(value = java.lang.String.class, inherited = false,
        allMethods = @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, receiver = Receiver.BORROWED_UNLESS_STATIC,
                borrowedParameters = true),
        allConstructors = @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, receiver = Receiver.SHARED,
                borrowedParameters = true, terminating = false))
interface StringAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    char charAt(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    int codePointAt(int p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int compareTo(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int compareToIgnoreCase(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String concat(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean contains(java.lang.CharSequence p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    boolean contentEquals(java.lang.StringBuffer p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    boolean contentEquals(java.lang.CharSequence p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean endsWith(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean equals(java.lang.Object p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean equalsIgnoreCase(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int hashCode();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int indexOf(java.lang.String p0, int p1, int p2);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int indexOf(java.lang.String p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int indexOf(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int indexOf(int p0, int p1, int p2);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int indexOf(int p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int indexOf(int p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean isBlank();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    boolean isEmpty();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int lastIndexOf(java.lang.String p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int lastIndexOf(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int lastIndexOf(int p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int lastIndexOf(int p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    int length();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String replace(char p0, char p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String replace(java.lang.CharSequence p0, java.lang.CharSequence p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean startsWith(java.lang.String p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean startsWith(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String strip();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String stripLeading();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String stripTrailing();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.String substring(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.String substring(int p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String toLowerCase();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String toLowerCase(java.util.Locale p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String toString();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String toUpperCase();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String toUpperCase(java.util.Locale p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    java.lang.String trim();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String valueOf(boolean p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String valueOf(char p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String valueOf(char[] p0, int p1, int p2);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String valueOf(char[] p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String valueOf(java.lang.Object p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String valueOf(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String valueOf(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String valueOf(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.String valueOf(long p0);
}
