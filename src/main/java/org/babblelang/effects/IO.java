package org.babblelang.effects;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicitly marks a Java type, method, or constructor as {@link Effect#IO} (clearing
 * {@code @HERMETIC} and {@code @DETERMINISTIC} ; BIP016-D3, D8).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface IO {
}
