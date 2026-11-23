package com.bookexchange.book;

import com.bookexchange.domain.BookSubmission;
import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.error.ApiException;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookService {

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

        BookSubmission book = books.save(new BookSubmission(exchange, request.submitterName(),
                request.bookTitle(), request.amazonUrl(), request.wrappingPaper()));

        // Listeners that react after commit (cover lookup) must never affect this request.
        events.publishEvent(new BookSubmittedEvent(book.getId()));
        return book;
    }
}
