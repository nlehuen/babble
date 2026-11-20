package org.babblelang.axioms.jakarta.annotation;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @jakarta.annotation.Nullable} may be null. */
@Axioms(targetName = "jakarta.annotation.Nullable")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NULLABLE)
interface NullableAxioms {
}
