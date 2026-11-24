package com.bookexchange.live;

import com.bookexchange.book.BookClaimedEvent;
import com.bookexchange.book.BookSubmittedEvent;
import com.bookexchange.exchange.ExchangeModeChangedEvent;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns "this was saved" into live updates. It runs only AFTER the database transaction has committed, so
 * nobody is ever told about something that then rolled back.
 *
 * <p>The payloads are the PUBLIC views, written out field by field so a new field can't leak by accident:
 * <ul>
 *   <li>book-added: who brought it and how it is wrapped, nothing about the book;</li>
 *   <li>book-claimed: what is public once a book is claimed (claimer, title, cover), never the book link;</li>
 *   <li>mode-changed: the new mode.</li>
 * </ul>
 */
@Component
public class LiveUpdateListener {

    public record BookAdded(Long id, String submitterName, String wrappingPaper) {}

    @JsonInclude(JsonInclude.Include.NON_NULL) // no cover yet: the field is simply left out
    public record BookClaimed(Long id, String claimedByName, Instant claimedAt, String bookTitle, String coverImageUrl) {}

    public record ModeChanged(String mode) {}

    private final ExchangeEventHub hub;

    public LiveUpdateListener(ExchangeEventHub hub) {
        this.hub = hub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookSubmitted(BookSubmittedEvent event) {
        hub.publish(event.exchangeCode(), "book-added",
                new BookAdded(event.bookId(), event.submitterName(), event.wrappingPaper()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookClaimed(BookClaimedEvent event) {
        hub.publish(event.exchangeCode(), "book-claimed",
                new BookClaimed(event.bookId(), event.claimedByName(), event.claimedAt(),
                        event.bookTitle(), event.coverImageUrl()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModeChanged(ExchangeModeChangedEvent event) {
        hub.publish(event.exchangeCode(), "mode-changed", new ModeChanged(event.mode().name()));
    }
}
