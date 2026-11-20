package org.babblelang.axioms.edu.umd.cs.findbugs.annotations;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @edu.umd.cs.findbugs.annotations.Nullable} may be null. */
@Axioms(targetName = "edu.umd.cs.findbugs.annotations.Nullable")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NULLABLE)
interface NullableAxioms {
}
