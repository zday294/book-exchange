package com.bookexchange.exchange;

import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.error.ApiException;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExchangeService {

    static final int MAX_CODE_ATTEMPTS = 10;
    static final int DEFAULT_PAGE_SIZE = 12;
    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_QUERY_LENGTH = 100;

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

    /**
     * The public list of exchanges: listed ones only, optionally filtered by a case-insensitive "name
     * contains" search and by status. Open exchanges come first, then closed, newest first within each.
     */
    @Transactional(readOnly = true)
    public ExchangeListResponse list(String query, ExchangeStatus status, int page, int size) {
        if (page < 0) throw ApiException.badRequest("page must be 0 or more");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String text = query == null ? "" : query.strip();
        if (text.length() > MAX_QUERY_LENGTH) {
            throw ApiException.badRequest("query can be at most " + MAX_QUERY_LENGTH + " characters");
        }

        List<ExchangeStatus> statuses = status == null ? List.of(ExchangeStatus.values()) : List.of(status);
        Page<ExchangeSummary> result = exchanges.searchListed(statuses, containsPattern(text), PageRequest.of(page, size));
        return new ExchangeListResponse(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    /** "%text%" over the lower-cased text, with LIKE's own wildcards escaped so a search is always literal. */
    static String containsPattern(String text) {
        String escaped = text.toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }
}
