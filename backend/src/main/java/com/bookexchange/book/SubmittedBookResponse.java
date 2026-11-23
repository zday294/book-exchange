package com.bookexchange.book;

import com.bookexchange.domain.BookSubmission;

/**
 * Response to the submitter. It echoes what they entered (including title and link); it is
 * NOT the public pile shape, which hides unclaimed books.
 */
public record SubmittedBookResponse(
        Long id,
        String submitterName,
        String bookTitle,
        String amazonUrl,
        String wrappingPaper,
        boolean claimed) {

    public static SubmittedBookResponse from(BookSubmission book) {
        return new SubmittedBookResponse(book.getId(), book.getSubmitterName(), book.getBookTitle(),
                book.getAmazonUrl(), book.getWrappingPaper(), book.isClaimed());
    }
}
