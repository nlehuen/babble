package org.babblelang.effects;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares {@link Effect#PURE} ({@code {@HERMETIC, @CONST, @DETERMINISTIC}}, with {@code @BORROWED}
 * default unless {@link Shared} is present) on a Java type, method, or constructor (BIP016-D8).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Pure {
}
