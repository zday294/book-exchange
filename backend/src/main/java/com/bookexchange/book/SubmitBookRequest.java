package com.bookexchange.book;

import com.bookexchange.validation.AmazonUrl;
import com.bookexchange.validation.WrappingPaper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for submitting a book. Text fields are trimmed before validation. */
public record SubmitBookRequest(
        @NotBlank @Size(max = 100) String submitterName,
        @NotBlank @Size(max = 300) String bookTitle,
        @NotBlank @Size(max = 2048) @AmazonUrl String amazonUrl,
        @NotBlank @Size(max = 50) @WrappingPaper String wrappingPaper) {

    public SubmitBookRequest {
        submitterName = trim(submitterName);
        bookTitle = trim(bookTitle);
        amazonUrl = trim(amazonUrl);
        wrappingPaper = trim(wrappingPaper);
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }
}
