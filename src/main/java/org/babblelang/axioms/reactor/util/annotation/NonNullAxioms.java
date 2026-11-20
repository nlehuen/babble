package org.babblelang.axioms.reactor.util.annotation;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @reactor.util.annotation.NonNull} is never null. */
@Axioms(targetName = "reactor.util.annotation.NonNull")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NON_NULL)
interface NonNullAxioms {
}
