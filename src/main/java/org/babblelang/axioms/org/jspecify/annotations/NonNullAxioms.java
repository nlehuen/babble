package org.babblelang.axioms.org.jspecify.annotations;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @org.jspecify.annotations.NonNull} is never null. */
@Axioms(targetName = "org.jspecify.annotations.NonNull")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NON_NULL)
interface NonNullAxioms {
}
