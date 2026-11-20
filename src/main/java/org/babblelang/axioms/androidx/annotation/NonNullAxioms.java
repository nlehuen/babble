package org.babblelang.axioms.androidx.annotation;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @androidx.annotation.NonNull} is never null. */
@Axioms(targetName = "androidx.annotation.NonNull")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NON_NULL)
interface NonNullAxioms {
}
