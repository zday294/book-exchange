package com.bookexchange.book;

import com.bookexchange.domain.BookSubmission;
import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeMode;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.error.ApiException;
import com.bookexchange.link.LinkCheckProperties;
import com.bookexchange.link.LinkChecker;
import com.bookexchange.link.LinkStatus;
import com.bookexchange.party.PartyService;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import java.net.URI;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BookService {

    private static final Logger log = LoggerFactory.getLogger(BookService.class);
    static final String LINK_NOT_FOUND =
            "That link doesn't seem to work (the page wasn't found). Check it and try again.";
    static final String ALREADY_SUBMITTED = "You have already submitted a book to this exchange";
    static final String BOOK_ALREADY_CLAIMED = "This book has already been claimed";
    static final String NOT_ACCEPTING_BOOKS =
            "This exchange has moved on to claiming, so no more books can be submitted";
    static final String NOT_CLAIMING_YET =
            "Claiming hasn't started yet; the host will open it when everyone has submitted their book";
    static final String YOU_ALREADY_CLAIMED = "You have already claimed a book in this exchange";
    static final String OWN_BOOK = "You can't claim a book you submitted yourself";
    private static final String ONE_CLAIM_INDEX = "uq_book_submission_one_claim_per_person";
    private static final String ONE_PER_PERSON_INDEX = "uq_book_submission_one_per_person";

    private final ExchangeRepository exchanges;
    private final BookSubmissionRepository books;
    private final ApplicationEventPublisher events;
    private final PartyService party;
    private final LinkChecker linkChecker;
    private final LinkCheckProperties linkCheck;
    private final TransactionTemplate transaction;

    public BookService(ExchangeRepository exchanges, BookSubmissionRepository books,
                       ApplicationEventPublisher events, PartyService party,
                       LinkChecker linkChecker, LinkCheckProperties linkCheck, TransactionTemplate transaction) {
        this.exchanges = exchanges;
        this.books = books;
        this.events = events;
        this.party = party;
        this.linkChecker = linkChecker;
        this.linkCheck = linkCheck;
        this.transaction = transaction;
    }

    /**
     * Saves a submitted book. Three steps, in this order: the cheap checks (exchange open, not already submitted),
     * then opening the link to see it isn't a dead page, then the real save with the checks repeated. The slow
     * network step sits between two short transactions, so no transaction or lock is held while waiting on a store.
     */
    public BookSubmission submit(String exchangeCode, SubmitBookRequest request) {
        if (linkCheck.enabled()) {
            transaction.executeWithoutResult(status -> requireCanSubmit(exchanges.findByCode(exchangeCode)
                    .orElseThrow(() -> ApiException.notFound("Exchange not found")), request));
            requireLinkNotDead(request.bookUrl());
        }
        return transaction.execute(status -> save(exchangeCode, request));
    }

    private void requireLinkNotDead(String bookUrl) {
        LinkStatus result;
        try {
            result = linkChecker.check(URI.create(bookUrl));
        } catch (RuntimeException e) {
            log.warn("Link check failed unexpectedly; letting the submission through", e);
            result = LinkStatus.UNVERIFIED;
        }
        if (result == LinkStatus.NOT_FOUND) {
            throw ApiException.invalidField("bookUrl", LINK_NOT_FOUND);
        }
    }

    private void requireCanSubmit(Exchange exchange, SubmitBookRequest request) {
        if (exchange.getStatus() != ExchangeStatus.OPEN) {
            throw ApiException.conflict("This exchange is closed and no longer accepts books");
        }
        if (exchange.getMode() != ExchangeMode.SUBMIT) {
            throw ApiException.conflict(NOT_ACCEPTING_BOOKS);
        }
        if (books.existsByExchangeAndSubmitter(exchange.getId(), request.submitterName())) {
            throw ApiException.conflict(ALREADY_SUBMITTED);
        }
    }

    private BookSubmission save(String exchangeCode, SubmitBookRequest request) {
        // Share-lock the exchange for the whole transaction: if the host switches the mode at the same
        // moment, one of the two waits for the other, so a book can never be saved after claiming began.
        Exchange exchange = exchanges.findByCodeForShare(exchangeCode)
                .orElseThrow(() -> ApiException.notFound("Exchange not found"));
        requireCanSubmit(exchange, request);

        BookSubmission book;
        try {
            // Flush now so the unique index (the real guarantee) fires inside this method,
            // turning a simultaneous duplicate into the same 409 as the check above.
            book = books.saveAndFlush(new BookSubmission(exchange, request.submitterName(),
                    request.bookTitle(), request.bookUrl(), request.wrappingPaper()));
        } catch (DataIntegrityViolationException e) {
            if (isOneSubmissionPerPersonViolation(e)) {
                throw ApiException.conflict(ALREADY_SUBMITTED);
            }
            throw e;
        }

        // Listeners that react after commit (cover lookup) must never affect this request.
        events.publishEvent(new BookSubmittedEvent(book.getId(), exchange.getCode(),
                book.getSubmitterName(), book.getWrappingPaper()));
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
        // Exclusive lock: claims in one exchange go one after the other, and so do they with the host switching the
        // mode and with a party's timer (a share lock here could deadlock with the party advancing the turn).
        Exchange exchange = exchanges.findByCodeForUpdate(exchangeCode)
                .orElseThrow(() -> ApiException.notFound("Exchange not found"));
        BookSubmission book = books.findByIdAndExchangeId(bookId, exchange.getId())
                .orElseThrow(() -> ApiException.notFound("Book not found"));
        if (exchange.getStatus() != ExchangeStatus.OPEN) {
            throw ApiException.conflict("This exchange is closed and no longer accepts claims");
        }
        if (exchange.getMode() == ExchangeMode.SUBMIT) {
            throw ApiException.conflict(NOT_CLAIMING_YET);
        }
        boolean inParty = exchange.getMode() == ExchangeMode.PARTY;
        if (inParty) {
            party.requireTurn(exchange, participantName); // only the person whose turn it is may claim
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

        if (inParty) {
            party.completeTurn(exchange, participantName); // their turn is over; the next one starts
        }

        BookSubmission claimed = books.findById(bookId).orElseThrow();
        events.publishEvent(new BookClaimedEvent(claimed.getId(), exchange.getCode(), claimed.getBookTitle(),
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
