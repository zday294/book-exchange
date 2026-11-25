package com.bookexchange.book;

import java.time.Instant;

/**
 * Published after a book is claimed, for live updates. It carries the PUBLIC claimed view only:
 * deliberately no book link, so no listener can ever forward it to the wrong people.
 */
public record BookClaimedEvent(Long bookId, String exchangeCode, String bookTitle, String coverImageUrl,
                               String claimedByName, Instant claimedAt) {
}
