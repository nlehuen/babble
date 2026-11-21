package org.babblelang.axioms;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Identifies the target Java type (by binary name, so third-party annotation jars need not be on
 * Babble's classpath) described by an axiom stub class ({@code BIP019} §2.5).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Axioms {
    /** Binary name of the target Java annotation or type, e.g. {@code "lombok.NonNull"}. */
    String targetName();
}
