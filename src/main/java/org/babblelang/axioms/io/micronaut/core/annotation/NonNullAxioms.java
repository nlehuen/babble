package org.babblelang.axioms.io.micronaut.core.annotation;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @io.micronaut.core.annotation.NonNull} is never null. */
@Axioms(targetName = "io.micronaut.core.annotation.NonNull")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NON_NULL)
interface NonNullAxioms {
}
