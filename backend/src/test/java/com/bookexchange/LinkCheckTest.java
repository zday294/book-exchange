package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeMode;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.link.LinkChecker;
import com.bookexchange.link.LinkStatus;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Submitting with the link check switched on and a fake checker (no real network, ever). */
@TestPropertySource(properties = "app.link-check.enabled=true")
class LinkCheckTest extends AbstractIntegrationTest {

    private static final String BN = "https://www.barnesandnoble.com/w/the-hobbit/1100054405?ean=9780547928227";
    private static final String MESSAGE = "That link doesn't seem to work (the page wasn't found). Check it and try again.";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExchangeRepository exchanges;
    @Autowired BookSubmissionRepository books;
    @MockitoBean LinkChecker checker;

    @BeforeEach
    void reset() {
        jdbc.update("delete from book_submission");
        Mockito.reset(checker);
        Exchange e = exchanges.findByCode("XMAS2026").orElseThrow();
        e.setMode(ExchangeMode.SUBMIT);
        e.setStatus(ExchangeStatus.OPEN);
        exchanges.save(e);
    }

    private ResultActions submit(String code, String name, String link) throws Exception {
        String body = json.writeValueAsString(Map.of("submitterName", name, "bookTitle", "T-" + name,
                "bookUrl", link, "wrappingPaper", "candy-cane"));
        return mvc.perform(post("/api/exchanges/{c}/books", code).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void aDeadLinkIsRejectedOnTheLinkFieldAndNothingIsSaved() throws Exception {
        when(checker.check(any())).thenReturn(LinkStatus.NOT_FOUND);

        submit("XMAS2026", "Zach", "https://www.amazon.com/dp/0000000000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details[0]").value("bookUrl: " + MESSAGE));
        assertThat(books.count()).isZero();
    }

    @Test
    void aLinkThatWorksIsAcceptedAndWasCheckedWithTheExactLink() throws Exception {
        when(checker.check(any())).thenReturn(LinkStatus.OK);
        submit("XMAS2026", "Zach", BN).andExpect(status().isCreated());
        verify(checker).check(URI.create(BN));
        assertThat(books.count()).isEqualTo(1);
    }

    @Test
    void anUnverifiedLinkNeverBlocksTheSubmission() throws Exception {
        when(checker.check(any())).thenReturn(LinkStatus.UNVERIFIED);
        submit("XMAS2026", "Zach", "https://www.amazon.com/dp/0547928221").andExpect(status().isCreated());
        assertThat(books.count()).isEqualTo(1);
    }

    @Test
    void aCheckerThatBlowsUpNeverBlocksTheSubmission() throws Exception {
        when(checker.check(any())).thenThrow(new IllegalStateException("boom"));
        submit("XMAS2026", "Zach", "https://www.amazon.com/dp/0547928221").andExpect(status().isCreated());
    }

    @Test
    void theCheckRunsOutsideAnyTransaction() throws Exception {
        AtomicBoolean inTransaction = new AtomicBoolean(true);
        when(checker.check(any())).thenAnswer(call -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return LinkStatus.OK;
        });
        submit("XMAS2026", "Zach", "https://www.amazon.com/dp/0547928221").andExpect(status().isCreated());
        verify(checker, times(1)).check(any());
        assertThat(inTransaction).isFalse();
    }

    @Test
    void requestsThatAreRefusedForOtherReasonsNeverTriggerAFetch() throws Exception {
        // already submitted
        when(checker.check(any())).thenReturn(LinkStatus.OK);
        submit("XMAS2026", "Zach", "https://www.amazon.com/dp/0547928221").andExpect(status().isCreated());
        Mockito.reset(checker);
        submit("XMAS2026", "zach", "https://www.amazon.com/dp/0547928221").andExpect(status().isConflict());

        // unknown exchange
        submit("NOPE9999", "Sam", "https://www.amazon.com/dp/0547928221").andExpect(status().isNotFound());

        // claiming has started
        Exchange e = exchanges.findByCode("XMAS2026").orElseThrow();
        e.setMode(ExchangeMode.CLAIM);
        exchanges.save(e);
        submit("XMAS2026", "Sam", "https://www.amazon.com/dp/0547928221").andExpect(status().isConflict());

        // closed
        e.setMode(ExchangeMode.SUBMIT);
        e.setStatus(ExchangeStatus.CLOSED);
        exchanges.save(e);
        submit("XMAS2026", "Sam", "https://www.amazon.com/dp/0547928221").andExpect(status().isConflict());

        // a link that fails the ordinary validation never reaches the checker either
        e.setStatus(ExchangeStatus.OPEN);
        exchanges.save(e);
        submit("XMAS2026", "Sam", "https://example.com/dp/1").andExpect(status().isBadRequest());

        verifyNoInteractions(checker);
    }

    @Test
    void aDeadLinkDoesNotUseUpThePersonsOneSubmission() throws Exception {
        when(checker.check(any())).thenReturn(LinkStatus.NOT_FOUND);
        submit("XMAS2026", "Zach", "https://www.amazon.com/dp/0000000000").andExpect(status().isBadRequest());
        when(checker.check(any())).thenReturn(LinkStatus.OK);
        submit("XMAS2026", "Zach", "https://www.amazon.com/dp/0547928221").andExpect(status().isCreated());
    }
}
