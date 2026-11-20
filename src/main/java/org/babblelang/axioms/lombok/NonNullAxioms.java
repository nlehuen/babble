package org.babblelang.axioms.lombok;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @lombok.NonNull} is never null. */
@Axioms(targetName = "lombok.NonNull")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NON_NULL)
interface NonNullAxioms {
}
