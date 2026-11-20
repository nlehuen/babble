package org.babblelang.axioms.android.annotation;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @android.annotation.Nullable} may be null. */
@Axioms(targetName = "android.annotation.Nullable")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NULLABLE)
interface NullableAxioms {
}
