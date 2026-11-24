package com.bookexchange.book;

import com.bookexchange.domain.BookSubmission;
import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.error.ApiException;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookService {

    static final String ALREADY_SUBMITTED = "You have already submitted a book to this exchange";
    static final String BOOK_ALREADY_CLAIMED = "This book has already been claimed";
    static final String YOU_ALREADY_CLAIMED = "You have already claimed a book in this exchange";
    static final String OWN_BOOK = "You can't claim a book you submitted yourself";
    private static final String ONE_CLAIM_INDEX = "uq_book_submission_one_claim_per_person";
    private static final String ONE_PER_PERSON_INDEX = "uq_book_submission_one_per_person";

    private final ExchangeRepository exchanges;
    private final BookSubmissionRepository books;
    private final ApplicationEventPublisher events;

    public BookService(ExchangeRepository exchanges, BookSubmissionRepository books,
                       ApplicationEventPublisher events) {
        this.exchanges = exchanges;
        this.books = books;
        this.events = events;
    }

    @Transactional
    public BookSubmission submit(String exchangeCode, SubmitBookRequest request) {
        Exchange exchange = exchanges.findByCode(exchangeCode)
                .orElseThrow(() -> ApiException.notFound("Exchange not found"));
        if (exchange.getStatus() != ExchangeStatus.OPEN) {
            throw ApiException.conflict("This exchange is closed and no longer accepts books");
        }

        if (books.existsByExchangeAndSubmitter(exchange.getId(), request.submitterName())) {
            throw ApiException.conflict(ALREADY_SUBMITTED);
        }

        BookSubmission book;
        try {
            // Flush now so the unique index (the real guarantee) fires inside this method,
            // turning a simultaneous duplicate into the same 409 as the check above.
            book = books.saveAndFlush(new BookSubmission(exchange, request.submitterName(),
                    request.bookTitle(), request.amazonUrl(), request.wrappingPaper()));
        } catch (DataIntegrityViolationException e) {
            if (isOneSubmissionPerPersonViolation(e)) {
                throw ApiException.conflict(ALREADY_SUBMITTED);
            }
            throw e;
        }

        // Listeners that react after commit (cover lookup) must never affect this request.
        events.publishEvent(new BookSubmittedEvent(book.getId()));
        return book;
    }

    private static boolean isOneSubmissionPerPersonViolation(Throwable e) {
        return hasMessage(e, ONE_PER_PERSON_INDEX);
    }

    private static boolean hasMessage(Throwable e, String text) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Claims a book for a participant and returns it in the claimer's view. The atomic conditional
     * update is the guard for two people racing for one book; the partial unique index is the guard
     * for one person racing for two books. Both losers get a 409.
     */
    @Transactional
    public BookView claim(String exchangeCode, Long bookId, String participantName) {
        Exchange exchange = findExchange(exchangeCode);
        BookSubmission book = books.findByIdAndExchangeId(bookId, exchange.getId())
                .orElseThrow(() -> ApiException.notFound("Book not found"));
        if (exchange.getStatus() != ExchangeStatus.OPEN) {
            throw ApiException.conflict("This exchange is closed and no longer accepts claims");
        }
        if (book.getSubmitterName().equalsIgnoreCase(participantName)) {
            throw ApiException.conflict(OWN_BOOK);
        }
        if (book.isClaimed()) {
            throw ApiException.conflict(BOOK_ALREADY_CLAIMED);
        }
        if (books.existsByExchangeIdAndClaimedByNameIgnoreCase(exchange.getId(), participantName)) {
            throw ApiException.conflict(YOU_ALREADY_CLAIMED);
        }

        int updated;
        try {
            updated = books.claimIfUnclaimed(bookId, exchange.getId(), participantName, Instant.now());
        } catch (DataIntegrityViolationException e) {
            if (hasMessage(e, ONE_CLAIM_INDEX)) {
                throw ApiException.conflict(YOU_ALREADY_CLAIMED);
            }
            throw e;
        }
        if (updated == 0) {
            throw ApiException.conflict(BOOK_ALREADY_CLAIMED);
        }

        BookSubmission claimed = books.findById(bookId).orElseThrow();
        events.publishEvent(new BookClaimedEvent(claimed.getId(), claimed.getBookTitle(),
                claimed.getCoverImageUrl(), claimed.getClaimedByName(), claimed.getClaimedAt()));
        return BookView.of(claimed, participantName);
    }

    @Transactional(readOnly = true)
    public java.util.List<BookView> list(String exchangeCode, String viewerName) {
        Exchange exchange = findExchange(exchangeCode);
        return books.findByExchangeIdOrderByIdAsc(exchange.getId()).stream()
                .map(b -> BookView.of(b, viewerName)).toList();
    }

    @Transactional(readOnly = true)
    public BookView get(String exchangeCode, Long bookId, String viewerName) {
        Exchange exchange = findExchange(exchangeCode);
        return books.findByIdAndExchangeId(bookId, exchange.getId())
                .map(b -> BookView.of(b, viewerName))
                .orElseThrow(() -> ApiException.notFound("Book not found"));
    }

    private Exchange findExchange(String exchangeCode) {
        return exchanges.findByCode(exchangeCode)
                .orElseThrow(() -> ApiException.notFound("Exchange not found"));
    }
}
