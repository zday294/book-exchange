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

/** The value must be an https link to one of the configured book-store hosts (Amazon or Barnes & Noble). */
@Documented
@Constraint(validatedBy = BookUrlValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface BookUrl {

    String message() default "must be an https link to Amazon or Barnes & Noble (amazon.com, amzn.to, a.co, barnesandnoble.com or bn.com)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
