package org.babblelang.axioms.androidx.annotation;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @androidx.annotation.Nullable} may be null. */
@Axioms(targetName = "androidx.annotation.Nullable")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NULLABLE)
interface NullableAxioms {
}
