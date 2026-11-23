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

/** The value must be an https link to one of the configured Amazon hosts. */
@Documented
@Constraint(validatedBy = AmazonUrlValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface AmazonUrl {

    String message() default "must be an https link to Amazon (amazon.com, amzn.to or a.co)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
