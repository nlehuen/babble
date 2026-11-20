package org.babblelang.effects;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares {@link Effect#SHARED} ({@code !@BORROWED}) on a Java type, method receiver, constructor,
 * or parameter (BIP016-D4, D8).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.PARAMETER})
public @interface Shared {
}
