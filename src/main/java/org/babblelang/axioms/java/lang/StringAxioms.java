package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;

import static org.babblelang.effects.Effect.*;

/**
 * Every method of {@code String} is pure, deterministic and borrows its receiver and
 * arguments ; those listed are also {@code nothrow}.
 */
@Axioms(value = java.lang.String.class, inherited = false,
        allMethods = @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, receiver = Receiver.BORROWED_UNLESS_STATIC,
                borrowedParameters = true),
        allConstructors = @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, receiver = Receiver.SHARED,
                borrowedParameters = true, terminating = false))
interface StringAxioms {
    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int compareTo(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int compareToIgnoreCase(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String concat(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    boolean contains(java.lang.CharSequence p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    boolean contentEquals(java.lang.StringBuffer p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    boolean contentEquals(java.lang.CharSequence p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    boolean endsWith(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    boolean equals(java.lang.Object p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    boolean equalsIgnoreCase(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int hashCode();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int indexOf(java.lang.String p0, int p1, int p2);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int indexOf(java.lang.String p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int indexOf(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int indexOf(int p0, int p1, int p2);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int indexOf(int p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int indexOf(int p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    boolean isBlank();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    boolean isEmpty();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int lastIndexOf(java.lang.String p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int lastIndexOf(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int lastIndexOf(int p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int lastIndexOf(int p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    int length();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String replace(char p0, char p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String replace(java.lang.CharSequence p0, java.lang.CharSequence p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    boolean startsWith(java.lang.String p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    boolean startsWith(java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String strip();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String stripLeading();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String stripTrailing();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String toLowerCase();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String toLowerCase(java.util.Locale p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String toString();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String toUpperCase();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String toUpperCase(java.util.Locale p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC,
            borrowedParameters = true, nothrow = true)
    java.lang.String trim();
}
