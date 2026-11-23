package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.domain.BookSubmission;
import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import com.bookexchange.support.RecordingCoverLookup;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import com.fasterxml.jackson.databind.ObjectMapper;

@Import(RecordingCoverLookup.class)
class SubmitBookTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ExchangeRepository exchanges;
    @Autowired BookSubmissionRepository books;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        RecordingCoverLookup.CALLS.clear();
        RecordingCoverLookup.FAIL.set(false);
    }

    private static Map<String, String> valid() {
        return new java.util.HashMap<>(Map.of(
                "submitterName", "Zach",
                "bookTitle", "The Hobbit",
                "amazonUrl", "https://www.amazon.com/dp/0547928221",
                "wrappingPaper", "candy-cane"));
    }

    private ResultActions submit(String exchangeCode, Object body) throws Exception {
        return mvc.perform(post("/api/exchanges/{code}/books", exchangeCode)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : json.writeValueAsString(body)));
    }

    @Test
    void validSubmissionIsSavedUnclaimed() throws Exception {
        submit("XMAS2026", valid())
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                        ".*/api/exchanges/XMAS2026/books/\\d+")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.submitterName").value("Zach"))
                .andExpect(jsonPath("$.bookTitle").value("The Hobbit"))
                .andExpect(jsonPath("$.amazonUrl").value("https://www.amazon.com/dp/0547928221"))
                .andExpect(jsonPath("$.wrappingPaper").value("candy-cane"))
                .andExpect(jsonPath("$.claimed").value(false));

        BookSubmission saved = books.findAll().stream()
                .filter(b -> b.getBookTitle().equals("The Hobbit")).findFirst().orElseThrow();
        assertThat(saved.getClaimedByName()).isNull();
        assertThat(saved.getClaimedAt()).isNull();
        String exchangeCode = jdbc.queryForObject(
                "SELECT e.code FROM exchange e JOIN book_submission b ON b.exchange_id = e.id WHERE b.id = ?",
                String.class, saved.getId());
        assertThat(exchangeCode).isEqualTo("XMAS2026");
    }

    @Test
    void textFieldsAreTrimmed() throws Exception {
        Map<String, String> body = valid();
        body.put("submitterName", "  Zach  ");
        body.put("bookTitle", "\tTrimmed Title \n");
        submit("XMAS2026", body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submitterName").value("Zach"))
                .andExpect(jsonPath("$.bookTitle").value("Trimmed Title"));
    }

    @Test
    void samePersonMaySubmitMoreThanOneBook() throws Exception {
        submit("XMAS2026", valid()).andExpect(status().isCreated());
        submit("XMAS2026", valid()).andExpect(status().isCreated());
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("missing submitterName", without("submitterName"), "submitterName"),
                Arguments.of("missing bookTitle", without("bookTitle"), "bookTitle"),
                Arguments.of("missing amazonUrl", without("amazonUrl"), "amazonUrl"),
                Arguments.of("missing wrappingPaper", without("wrappingPaper"), "wrappingPaper"),
                Arguments.of("blank submitterName", with("submitterName", "   "), "submitterName"),
                Arguments.of("blank bookTitle", with("bookTitle", ""), "bookTitle"),
                Arguments.of("submitterName too long", with("submitterName", "x".repeat(101)), "submitterName"),
                Arguments.of("bookTitle too long", with("bookTitle", "x".repeat(301)), "bookTitle"),
                Arguments.of("amazonUrl too long", with("amazonUrl",
                        "https://www.amazon.com/dp/" + "x".repeat(2048)), "amazonUrl"),
                Arguments.of("http link", with("amazonUrl", "http://www.amazon.com/dp/1"), "amazonUrl"),
                Arguments.of("non-Amazon host", with("amazonUrl", "https://example.com/dp/1"), "amazonUrl"),
                Arguments.of("lookalike host", with("amazonUrl", "https://amazon.com.evil.com/dp/1"), "amazonUrl"),
                Arguments.of("not a url", with("amazonUrl", "not a url"), "amazonUrl"),
                Arguments.of("unknown paper", with("wrappingPaper", "neon-skulls"), "wrappingPaper"));
    }

    private static Map<String, String> without(String key) {
        Map<String, String> m = valid();
        m.remove(key);
        return m;
    }

    private static Map<String, String> with(String key, String value) {
        Map<String, String> m = valid();
        m.put(key, value);
        return m;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void invalidRequestsAreRejectedWith400(String name, Map<String, String> body, String field) throws Exception {
        long before = books.count();
        submit("XMAS2026", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details[?(@ =~ /^" + field + ": .*/)]").isNotEmpty());
        assertThat(books.count()).isEqualTo(before);
        assertThat(RecordingCoverLookup.CALLS).isEmpty();
    }

    @Test
    void severalProblemsAreReportedTogether() throws Exception {
        submit("XMAS2026", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(4));
    }

    @Test
    void malformedJsonIsRejectedWith400() throws Exception {
        submit("XMAS2026", "{oops").andExpect(status().isBadRequest());
    }

    @Test
    void unknownExchangeIsNotFound() throws Exception {
        submit("NOSUCH", valid())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Exchange not found"));
    }

    @Test
    void closedExchangeIsRejectedWithConflict() throws Exception {
        Exchange closed = new Exchange("CLOSEDONE", "Closed one");
        closed.setStatus(ExchangeStatus.CLOSED);
        exchanges.save(closed);

        long before = books.count();
        submit("CLOSEDONE", valid())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("closed")));
        assertThat(books.count()).isEqualTo(before);
    }

    @Test
    void coverLookupRunsAfterTheBookIsSaved() throws Exception {
        String id = json.readTree(submit("XMAS2026", valid())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asText();

        await().untilAsserted(() -> assertThat(RecordingCoverLookup.CALLS).contains(Long.valueOf(id)));
    }

    @Test
    void coverLookupFailureStillReturns201AndKeepsTheBook() throws Exception {
        RecordingCoverLookup.FAIL.set(true);

        String id = json.readTree(submit("XMAS2026", valid())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asText();

        await().untilAsserted(() -> assertThat(RecordingCoverLookup.CALLS).contains(Long.valueOf(id)));
        assertThat(books.findById(Long.valueOf(id))).isPresent();
    }
}
