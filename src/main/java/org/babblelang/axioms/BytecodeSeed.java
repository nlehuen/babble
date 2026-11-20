package org.babblelang.axioms;

import org.babblelang.effects.Effect;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What the bytecode analyzer assumes about a method it reaches instead of analysing it (BIP019) :
 * a seed. Natives, notably, can only be known this way.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BytecodeSeed {
    /** The guarantees : {@code PURE}, {@code HERMETIC}, {@code DETERMINISTIC}, {@code NOTHROW}. */
    Effect[] value();
}
