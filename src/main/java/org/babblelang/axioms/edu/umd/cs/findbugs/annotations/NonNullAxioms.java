package org.babblelang.axioms.edu.umd.cs.findbugs.annotations;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @edu.umd.cs.findbugs.annotations.NonNull} is never null. */
@Axioms(targetName = "edu.umd.cs.findbugs.annotations.NonNull")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NON_NULL)
interface NonNullAxioms {
}
