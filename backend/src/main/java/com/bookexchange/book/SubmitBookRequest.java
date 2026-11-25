package com.bookexchange.book;

import com.bookexchange.validation.BookUrl;
import com.bookexchange.validation.WrappingPaper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for submitting a book. Text fields are trimmed before validation. */
@Schema(description = "A book to add to the exchange pile. Text fields are trimmed before validation.")
public record SubmitBookRequest(
        @Schema(description = "Name of the person submitting the book", example = "Zach")
        @NotBlank @Size(max = 100) String submitterName,
        @Schema(description = "Title of the book. Hidden from other participants until the book is claimed.",
                example = "The Hobbit")
        @NotBlank @Size(max = 300) String bookTitle,
        @Schema(example = "https://www.amazon.com/dp/054792822X")
        @NotBlank @Size(max = 2048) @BookUrl String bookUrl,
        @Schema(description = "Key of the wrapping paper design", example = "candy-cane")
        @NotBlank @Size(max = 50) @WrappingPaper String wrappingPaper) {

    public SubmitBookRequest {
        submitterName = trim(submitterName);
        bookTitle = trim(bookTitle);
        bookUrl = trim(bookUrl);
        wrappingPaper = trim(wrappingPaper);
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }
}
