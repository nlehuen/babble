package org.babblelang.axioms.org.jspecify.annotations;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @org.jspecify.annotations.Nullable} may be null. */
@Axioms(targetName = "org.jspecify.annotations.Nullable")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NULLABLE)
interface NullableAxioms {
}
