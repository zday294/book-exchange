package com.bookexchange.exchange;

import com.bookexchange.domain.Exchange;
import com.bookexchange.error.ApiException;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExchangeService {

    static final int MAX_CODE_ATTEMPTS = 10;

    private final ExchangeRepository exchanges;
    private final BookSubmissionRepository books;
    private final ExchangeCodeGenerator codes;

    public ExchangeService(ExchangeRepository exchanges, BookSubmissionRepository books,
                           ExchangeCodeGenerator codes) {
        this.exchanges = exchanges;
        this.books = books;
        this.codes = codes;
    }

    /**
     * Deliberately NOT transactional: each attempt is its own transaction, so a lost race on the
     * unique code index (which aborts that transaction) can simply be retried with a new code.
     */
    public ExchangeResponse create(CreateExchangeRequest request) {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = codes.next();
            if (exchanges.findByCode(code).isPresent()) {
                continue; // collision with an existing exchange
            }
            Exchange exchange = new Exchange(code, request.name());
            exchange.setDescription(request.description());
            exchange.setCreatedByName(request.organizerName());
            exchange.setListed(request.isListed());
            try {
                exchanges.saveAndFlush(exchange);
            } catch (DataIntegrityViolationException e) {
                continue; // someone else took the code between our check and the insert
            }
            return get(code);
        }
        throw new IllegalStateException("Could not generate a unique exchange code");
    }

    @Transactional(readOnly = true)
    public ExchangeResponse get(String code) {
        Exchange exchange = exchanges.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("Exchange not found"));
        return ExchangeResponse.of(exchange,
                books.countByExchangeId(exchange.getId()),
                books.countByExchangeIdAndClaimedByNameIsNotNull(exchange.getId()));
    }
}
