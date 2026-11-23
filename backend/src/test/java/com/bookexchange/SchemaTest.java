package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bookexchange.domain.BookSubmission;
import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The context only starts if Liquibase applied the changelog and Hibernate's
 * ddl-auto=validate accepts the resulting schema, so these tests cover that too.
 */
class SchemaTest extends AbstractIntegrationTest {

    @Autowired ExchangeRepository exchanges;
    @Autowired BookSubmissionRepository books;
    @Autowired JdbcTemplate jdbc;

    @Test
    void seededExchangeExists() {
        Exchange seeded = exchanges.findByCode("XMAS2026").orElseThrow();
        assertThat(seeded.getStatus()).isEqualTo(ExchangeStatus.OPEN);
        assertThat(seeded.isListed()).isTrue();
        assertThat(seeded.getCreatedAt()).isNotNull();
    }

    @Test
    void liquibaseRecordedEveryChangeset() {
        Integer applied = jdbc.queryForObject("SELECT count(*) FROM databasechangelog", Integer.class);
        assertThat(applied).isEqualTo(5); // 001, 002, 003 (two), 004
    }

    @Test
    void aPersonCanClaimOnlyOneBookPerExchangeCaseInsensitively() {
        Exchange exchange = exchanges.save(new Exchange("ONECLAIM", "One claim"));
        BookSubmission first = books.save(book(exchange, "First"));
        BookSubmission second = books.save(book(exchange, "Second"));

        first.claim("Alex", Instant.now());
        books.saveAndFlush(first);

        second.claim("ALEX", Instant.now());
        assertThatThrownBy(() -> books.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameNameMayClaimInDifferentExchanges() {
        Exchange a = exchanges.save(new Exchange("EXCH-A", "A"));
        Exchange b = exchanges.save(new Exchange("EXCH-B", "B"));
        BookSubmission inA = books.save(book(a, "In A"));
        BookSubmission inB = books.save(book(b, "In B"));

        inA.claim("Alex", Instant.now());
        inB.claim("Alex", Instant.now());
        books.saveAllAndFlush(java.util.List.of(inA, inB));

        assertThat(books.findByExchangeIdOrderByIdAsc(a.getId())).hasSize(1);
    }

    @Test
    void claimNameAndTimestampMustBeSetTogether() {
        Exchange exchange = exchanges.save(new Exchange("HALFCLAIM", "Half claim"));
        BookSubmission b = books.save(book(exchange, "Half"));

        b.claim("Alex", null);
        assertThatThrownBy(() -> books.saveAndFlush(b))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static BookSubmission book(Exchange exchange, String title) {
        return new BookSubmission(exchange, "Zach", title, "https://www.amazon.com/dp/0547928221", "candy-cane");
    }
}
