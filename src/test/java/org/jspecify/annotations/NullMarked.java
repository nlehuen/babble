package org.jspecify.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A stand-in for JSpecify's annotation of that name, which Babble matches by name : no jar needed. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.METHOD, ElementType.MODULE, ElementType.CONSTRUCTOR})
public @interface NullMarked {
}
