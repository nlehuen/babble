package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;
import org.babblelang.axioms.Foldable;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.Nullable;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.Profile.Receiver;
import org.babblelang.effects.Idempotent;

import static org.babblelang.effects.Effect.*;

/**
 * Every method of {@code String} is pure, deterministic and borrows its receiver and
 * arguments ; some are also {@code nothrow}. What the bytecode analyzer assumes ({@link BytecodeSeed})
 * and what the optimizer may call at compile time ({@link Foldable}) are stated separately.
 *
 * <p>QUESTIONABLE : the
 * analyzer counts {@code valueOf} as {@code nothrow}, the effect checker does not.
 */
@Axioms(value = java.lang.String.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET,
        allMethods = @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true),
        allConstructors = @Profile(value = {PURE, HERMETIC, DETERMINISTIC}, terminating = false, receiver = Receiver.SHARED, borrowedParameters = true))
interface StringAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object charAt(int p0);

    @NonNull
    java.lang.Object chars();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object codePointAt(int p0);

    @NonNull
    java.lang.Object codePoints();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object compareTo(@NonNull java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true)
    java.lang.Object compareToIgnoreCase(@NonNull java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object concat(@NonNull java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object contains(@NonNull java.lang.CharSequence p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true)
    java.lang.Object contentEquals(java.lang.StringBuffer p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true)
    java.lang.Object contentEquals(java.lang.CharSequence p0);

    @NonNull
    java.lang.Object copyValueOf(char[] p0, int p1, int p2);

    @NonNull
    java.lang.Object copyValueOf(char[] p0);

    @NonNull
    java.lang.Object describeConstable();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object endsWith(@NonNull java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean equals(@Nullable java.lang.Object p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object equalsIgnoreCase(@Nullable java.lang.String p0);

    @NonNull
    java.lang.Object format(@Nullable java.lang.String p0, @Nullable java.lang.Object[] p1);

    @NonNull
    java.lang.Object format(@Nullable java.util.Locale p0, @Nullable java.lang.String p1, @Nullable java.lang.Object[] p2);

    @NonNull
    java.lang.Object formatted(@Nullable java.lang.Object[] p0);

    @NonNull
    java.lang.Object getBytes();

    @NonNull
    java.lang.Object getBytes(java.lang.String p0);

    @NonNull
    java.lang.Object getBytes(java.nio.charset.Charset p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int hashCode();

    @NonNull
    java.lang.Object indent(int p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object indexOf(@NonNull java.lang.String p0, int p1, int p2);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object indexOf(@NonNull java.lang.String p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object indexOf(@NonNull java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object indexOf(int p0, int p1, int p2);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object indexOf(int p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object indexOf(int p0);

    @NonNull
    java.lang.Object intern();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object isBlank();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object isEmpty();

    @NonNull
    java.lang.Object join(java.lang.CharSequence p0, java.lang.CharSequence[] p1);

    @NonNull
    java.lang.Object join(java.lang.CharSequence p0, java.lang.Iterable p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object lastIndexOf(@NonNull java.lang.String p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object lastIndexOf(@NonNull java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object lastIndexOf(int p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object lastIndexOf(int p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object length();

    @NonNull
    java.lang.Object lines();

    
    java.lang.Object matches(@NonNull java.lang.String p0);

    @NonNull
    java.lang.Object repeat(int p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @NonNull
    java.lang.Object replace(char p0, char p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @NonNull
    java.lang.Object replace(@NonNull java.lang.CharSequence p0, @NonNull java.lang.CharSequence p1);

    @NonNull
    java.lang.Object replaceAll(@NonNull java.lang.String p0, @NonNull java.lang.String p1);

    @NonNull
    java.lang.Object replaceFirst(@NonNull java.lang.String p0, @NonNull java.lang.String p1);

    @NonNull
    java.lang.Object resolveConstantDesc(java.lang.invoke.MethodHandles.Lookup p0);

    @NonNull
    java.lang.Object split(@NonNull java.lang.String p0, int p1);

    @NonNull
    java.lang.Object split(@NonNull java.lang.String p0);

    @NonNull
    java.lang.Object splitWithDelimiters(java.lang.String p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object startsWith(@NonNull java.lang.String p0, int p1);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object startsWith(@NonNull java.lang.String p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object strip();

    @NonNull
    java.lang.Object stripIndent();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @NonNull
    java.lang.Object stripLeading();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @NonNull
    java.lang.Object stripTrailing();

    @NonNull
    java.lang.Object subSequence(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC}) @NonNull
    java.lang.Object substring(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC}) @NonNull
    java.lang.Object substring(int p0);

    @NonNull
    java.lang.Object toCharArray();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object toLowerCase();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object toLowerCase(java.util.Locale p0);

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.String toString();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object toUpperCase();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object toUpperCase(java.util.Locale p0);

    @NonNull
    java.lang.Object transform(java.util.function.Function p0);

    @NonNull
    java.lang.Object translateEscapes();

    @Profile(value = {PURE, HERMETIC, DETERMINISTIC, NOTHROW}, receiver = Receiver.BORROWED_UNLESS_STATIC, borrowedParameters = true, nothrow = true) @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent @NonNull
    java.lang.Object trim();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(boolean p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(char p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(@Nullable char[] p0, int p1, int p2);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(@Nullable char[] p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(@Nullable java.lang.Object p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(long p0);
}
