package org.babblelang.axioms.javax.annotation;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @javax.annotation.Nullable} may be null. */
@Axioms(targetName = "javax.annotation.Nullable")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NULLABLE)
interface NullableAxioms {
}
