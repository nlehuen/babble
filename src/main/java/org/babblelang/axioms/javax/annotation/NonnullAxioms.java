package org.babblelang.axioms.javax.annotation;

import org.babblelang.axioms.AnnotationMeaning;
import org.babblelang.axioms.Axioms;

/** What host code annotated {@code @javax.annotation.Nonnull} is never null (only with {@code when = ALWAYS}, or none). */
@Axioms(targetName = "javax.annotation.Nonnull")
@AnnotationMeaning(value = AnnotationMeaning.Meaning.NON_NULL, onlyWhenAlways = true)
interface NonnullAxioms {
}
