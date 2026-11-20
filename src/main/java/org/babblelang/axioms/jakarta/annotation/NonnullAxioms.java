package org.babblelang.axioms.jakarta.annotation;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @jakarta.annotation.Nonnull} is never null. */
@Axioms(targetName = "jakarta.annotation.Nonnull")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NON_NULL)
interface NonnullAxioms {
}
