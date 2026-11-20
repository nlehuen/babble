package javax.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A stand-in for JSR 305's, with its {@code when}, for JavaNullabilityTestCase. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER})
public @interface Nonnull {
    enum When { ALWAYS, UNKNOWN, MAYBE, NEVER }

    When when() default When.ALWAYS;
}
