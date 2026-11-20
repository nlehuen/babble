package org.babblelang.axioms.org.jspecify.annotations;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @org.jspecify.annotations.NullMarked} marks a scope where unannotated types are non-null. */
@Axioms(targetName = "org.jspecify.annotations.NullMarked")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NULL_MARKED)
interface NullMarkedAxioms {
}
