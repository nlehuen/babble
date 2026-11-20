package org.babblelang.axioms.io.micronaut.core.annotation;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @io.micronaut.core.annotation.Nullable} may be null. */
@Axioms(targetName = "io.micronaut.core.annotation.Nullable")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NULLABLE)
interface NullableAxioms {
}
