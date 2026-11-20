package org.babblelang.axioms;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a stub : the instances of its target (not of its subtypes) are deeply immutable — when its
 * type arguments are, and are given, if {@link #ifTypeArgumentsAre()} (BIP019).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DeeplyImmutable {
    /** Only when every type argument is given and deeply immutable. */
    boolean ifTypeArgumentsAre() default true;
}
