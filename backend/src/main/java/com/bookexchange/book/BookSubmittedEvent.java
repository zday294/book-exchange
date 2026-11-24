package com.bookexchange.book;

/**
 * Published when a book has been saved. Used to start the (non-blocking) cover lookup and to tell people
 * watching the exchange that a new present is under the tree. Carries only what everyone may see.
 */
public record BookSubmittedEvent(Long bookId, String exchangeCode, String submitterName, String wrappingPaper) {
}
