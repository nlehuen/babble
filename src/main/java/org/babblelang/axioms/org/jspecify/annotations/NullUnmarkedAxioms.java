package org.babblelang.axioms.org.jspecify.annotations;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @org.jspecify.annotations.NullUnmarked} undoes an enclosing null-marked scope. */
@Axioms(targetName = "org.jspecify.annotations.NullUnmarked")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NULL_UNMARKED)
interface NullUnmarkedAxioms {
}
