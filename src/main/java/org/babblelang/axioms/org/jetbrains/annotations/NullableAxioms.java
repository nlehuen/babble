package org.babblelang.axioms.org.jetbrains.annotations;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @org.jetbrains.annotations.Nullable} may be null. */
@Axioms(targetName = "org.jetbrains.annotations.Nullable")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NULLABLE)
interface NullableAxioms {
}
