package com.bookexchange.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** The value must be the key of a known wrapping paper. */
@Documented
@Constraint(validatedBy = WrappingPaperValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface WrappingPaper {

    String message() default "must be a known wrapping paper";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
