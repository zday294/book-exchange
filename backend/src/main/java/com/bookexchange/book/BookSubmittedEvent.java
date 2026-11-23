package com.bookexchange.book;

/** Published when a book has been saved; used to start the (non-blocking) cover lookup. */
public record BookSubmittedEvent(Long bookId) {
}
