package org.babblelang.axioms.org.jetbrains.annotations;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @org.jetbrains.annotations.NotNull} is never null. */
@Axioms(targetName = "org.jetbrains.annotations.NotNull")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NON_NULL)
interface NotNullAxioms {
}
