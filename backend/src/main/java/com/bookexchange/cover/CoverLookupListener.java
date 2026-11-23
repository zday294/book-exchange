package com.bookexchange.cover;

import com.bookexchange.book.BookSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs the cover lookup after the submission has been committed and off the request thread.
 * Any failure is logged and swallowed: a missing cover is acceptable, a failed submission is not.
 */
@Component
public class CoverLookupListener {

    private static final Logger log = LoggerFactory.getLogger(CoverLookupListener.class);

    private final CoverLookup coverLookup;

    public CoverLookupListener(CoverLookup coverLookup) {
        this.coverLookup = coverLookup;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookSubmitted(BookSubmittedEvent event) {
        try {
            coverLookup.resolveCover(event.bookId());
        } catch (RuntimeException e) {
            log.warn("Cover lookup failed for book {}", event.bookId(), e);
        }
    }
}
