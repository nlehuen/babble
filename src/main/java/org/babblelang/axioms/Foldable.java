package org.babblelang.axioms;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The optimizer may treat a call as pure, {@code nothrow} and terminating, and call it at
 * compile time on constant arguments (BIP019).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Foldable {
}
