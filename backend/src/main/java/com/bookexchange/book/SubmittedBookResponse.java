package com.bookexchange.book;

import com.bookexchange.domain.BookSubmission;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response to the submitter. It echoes what they entered (including title and link); it is
 * NOT the public pile shape, which hides unclaimed books.
 */
@Schema(description = "The submitted book as seen by its submitter. Not the public pile view, which hides "
        + "unclaimed books.")
public record SubmittedBookResponse(
        @Schema(example = "12") Long id,
        @Schema(example = "Zach") String submitterName,
        @Schema(example = "The Hobbit") String bookTitle,
        @Schema(example = "https://www.amazon.com/dp/054792822X") String bookUrl,
        @Schema(example = "candy-cane") String wrappingPaper,
        @Schema(description = "Always false for a newly submitted book") boolean claimed) {

    public static SubmittedBookResponse from(BookSubmission book) {
        return new SubmittedBookResponse(book.getId(), book.getSubmitterName(), book.getBookTitle(),
                book.getBookUrl(), book.getWrappingPaper(), book.isClaimed());
    }
}
