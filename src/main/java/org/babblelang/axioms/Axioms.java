package org.babblelang.axioms;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a stub stating axioms about another class (BIP019) : the effect checker trusts them
 * instead of proving them. The stub's methods mirror the target's, matched by name and
 * parameter types (an override of a stub method, in a subtype, matches it too) ; a stub lives
 * in {@code org.babblelang.axioms.<target's package>} and is named {@code <Target>Axioms}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Axioms {
    /** The class the axioms are about. */
    Class<?> value();

    /** Whether the axioms also hold for the subtypes of {@link #value()}. */
    boolean inherited() default true;

    /** At most one profile, of every method of the target the stub doesn't list. */
    Profile[] allMethods() default {};

    /** At most one profile, of every constructor of the target (and of its subtypes when inherited). */
    Profile[] allConstructors() default {};

    /** Where, in the target's signatures, unannotated type arguments are non-null (JSpecify's {@code @NullMarked}). */
    enum NullMarked {
        /** Nowhere. */
        NO,
        /** In the target's own methods and constructors. */
        TARGET,
        /** In those of the target and of all its subtypes. */
        SUBTYPES
    }

    /** Whether the target's signatures are null-marked. */
    NullMarked nullMarked() default NullMarked.NO;
}
