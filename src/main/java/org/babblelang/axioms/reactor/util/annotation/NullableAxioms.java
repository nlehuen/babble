package org.babblelang.axioms.reactor.util.annotation;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @reactor.util.annotation.Nullable} may be null. */
@Axioms(targetName = "reactor.util.annotation.Nullable")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NULLABLE)
interface NullableAxioms {
}
