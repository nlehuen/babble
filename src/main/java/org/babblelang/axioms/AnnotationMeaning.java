package org.babblelang.axioms;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a stub about an annotation : what that annotation means to the analysis, wherever host code
 * uses it (BIP019-D10).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AnnotationMeaning {
    enum Meaning {
        /** The annotated type, result or parameter is never null. */
        NON_NULL,
        /** It may be null. */
        NULLABLE,
        /** In the annotated scope, unannotated types are non-null. */
        NULL_MARKED,
        /** In the annotated scope, they are not (it undoes an enclosing {@code NULL_MARKED}). */
        NULL_UNMARKED
    }

    Meaning value();

    /** Only when the annotation's {@code when} element is absent or {@code ALWAYS} (as {@code javax.annotation.Nonnull}'s). */
    boolean onlyWhenAlways() default false;
}
