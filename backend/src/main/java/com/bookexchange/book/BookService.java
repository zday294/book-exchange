package com.bookexchange.book;

import com.bookexchange.domain.BookSubmission;
import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.error.ApiException;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookService {

    static final String ALREADY_SUBMITTED = "You have already submitted a book to this exchange";
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
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(ONE_PER_PERSON_INDEX)) {
                return true;
            }
        }
        return false;
    }
}
