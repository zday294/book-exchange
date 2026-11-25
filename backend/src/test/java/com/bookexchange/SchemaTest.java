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
        assertThat(applied).isEqualTo(10); // 001, 002, 003 (two), 004, 005, 006, 007, 008, 009
    }

    @Test
    void aPersonCanClaimOnlyOneBookPerExchangeCaseInsensitively() {
        Exchange exchange = exchanges.save(new Exchange("ONECLAIM", "One claim"));
        BookSubmission first = books.save(book(exchange, "Zach", "First"));
        BookSubmission second = books.save(book(exchange, "Zoe", "Second"));

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
        BookSubmission inA = books.save(book(a, "Zach", "In A"));
        BookSubmission inB = books.save(book(b, "Zach", "In B"));

        inA.claim("Alex", Instant.now());
        inB.claim("Alex", Instant.now());
        books.saveAllAndFlush(java.util.List.of(inA, inB));

        assertThat(books.findByExchangeIdOrderByIdAsc(a.getId())).hasSize(1);
    }

    @Test
    void newExchangesStartInSubmitModeAndOnlyKnownModesAreAllowed() {
        Exchange e = exchanges.save(new Exchange("MODECHK1", "Mode check"));
        assertThat(jdbc.queryForObject("select mode from exchange where id = ?", String.class, e.getId())).isEqualTo("SUBMIT");
        assertThat(jdbc.queryForObject("select mode from exchange where code = 'XMAS2026'", String.class)).isEqualTo("SUBMIT");

        jdbc.update("update exchange set mode = 'CLAIM' where id = ?", e.getId()); // fine
        jdbc.update("update exchange set mode = 'PARTY' where id = ?", e.getId()); // party mode exists now
        assertThatThrownBy(() -> jdbc.update("update exchange set mode = 'DANCE' where id = ?", e.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void coverAspectRatioIsOptionalAndMustBeSensible() {
        Exchange exchange = exchanges.save(new Exchange("RATIOCHK", "Ratio check"));
        BookSubmission b = books.save(book(exchange, "Zach", "Shaped"));
        assertThat(jdbc.queryForObject("select cover_aspect_ratio from book_submission where id = ?", java.math.BigDecimal.class, b.getId())).isNull();

        jdbc.update("update book_submission set cover_aspect_ratio = 0.67 where id = ?", b.getId());
        assertThatThrownBy(() -> jdbc.update("update book_submission set cover_aspect_ratio = 0 where id = ?", b.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update book_submission set cover_aspect_ratio = 50 where id = ?", b.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void claimNameAndTimestampMustBeSetTogether() {
        Exchange exchange = exchanges.save(new Exchange("HALFCLAIM", "Half claim"));
        BookSubmission b = books.save(book(exchange, "Zach", "Half"));

        b.claim("Alex", null);
        assertThatThrownBy(() -> books.saveAndFlush(b))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPersonCanSubmitOnlyOneBookPerExchangeCaseInsensitively() {
        Exchange exchange = exchanges.save(new Exchange("ONESUBMIT", "One submission"));
        books.saveAndFlush(book(exchange, "Zach", "First"));

        assertThatThrownBy(() -> books.saveAndFlush(book(exchange, "ZACH", "Second")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameNameMaySubmitInDifferentExchanges() {
        Exchange a = exchanges.save(new Exchange("SUB-A", "A"));
        Exchange b = exchanges.save(new Exchange("SUB-B", "B"));
        books.saveAllAndFlush(java.util.List.of(book(a, "Zach", "In A"), book(b, "Zach", "In B")));
    }

    private static BookSubmission book(Exchange exchange, String submitter, String title) {
        return new BookSubmission(exchange, submitter, title, "https://www.amazon.com/dp/0547928221", "candy-cane");
    }
}
