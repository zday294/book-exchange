package com.bookexchange.validation;

import com.bookexchange.book.WrappingPapers;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;

@Component
public class WrappingPaperValidator implements ConstraintValidator<WrappingPaper, String> {

    private final WrappingPapers papers;

    public WrappingPaperValidator(WrappingPapers papers) {
        this.papers = papers;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.isBlank() || papers.isKnown(value);
    }
}
