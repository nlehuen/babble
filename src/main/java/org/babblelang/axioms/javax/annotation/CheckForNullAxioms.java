package org.babblelang.axioms.javax.annotation;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @javax.annotation.CheckForNull} may be null. */
@Axioms(targetName = "javax.annotation.CheckForNull")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NULLABLE)
interface CheckForNullAxioms {
}
