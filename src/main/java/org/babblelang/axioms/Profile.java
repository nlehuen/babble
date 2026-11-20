package org.babblelang.axioms;

import org.babblelang.effects.Effect;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The effect profile of a method or constructor, stated literally : what the effect checker
 * assumes when it calls it (BIP019). Its fields are those of the checker's call profiles, so
 * the axioms written before BIP019 carry over exactly ; friendlier forms may replace them later.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Profile {
    /** How a call treats its receiver. */
    enum Receiver {
        /** The callee doesn't keep its receiver. */
        BORROWED,
        /** The callee may keep its receiver. */
        SHARED,
        /** Borrowed, except for static methods, which have none. */
        BORROWED_UNLESS_STATIC
    }

    /** The guarantees : {@code PURE}, {@code HERMETIC}, {@code DETERMINISTIC}, {@code NOTHROW}. */
    Effect[] value();

    /** The callee mutates its receiver (in its effect set). */
    boolean mutThis() default false;

    /** The callee borrows its receiver (in its effect set). */
    boolean borrowThis() default true;

    /** The callee always terminates. */
    boolean terminating() default true;

    /** A call mutates the receiver. */
    boolean mutReceiver() default false;

    /** How a call treats the receiver. */
    Receiver receiver() default Receiver.BORROWED;

    /** Every parameter is borrowed ; otherwise the callee may keep them. */
    boolean borrowedParameters() default false;

    /** A call never throws, so it needs no call check. */
    boolean nothrow() default false;

    /**
     * QUESTIONABLE : deterministic only where the method is declared by a class other than
     * {@code Object} and not an interface (an override is assumed deterministic).
     */
    boolean deterministicIfOverridden() default false;
}
