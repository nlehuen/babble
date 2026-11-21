package org.babblelang.axioms;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks an {@link Axioms} stub whose target annotation means nullable ({@code BIP019} §2.5). */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MeansNullable {
}
