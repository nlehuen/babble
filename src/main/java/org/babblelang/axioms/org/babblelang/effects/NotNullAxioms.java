package org.babblelang.axioms.org.babblelang.effects;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @org.babblelang.effects.NotNull} is never null. */
@Axioms(targetName = "org.babblelang.effects.NotNull")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NON_NULL)
interface NotNullAxioms {
}
