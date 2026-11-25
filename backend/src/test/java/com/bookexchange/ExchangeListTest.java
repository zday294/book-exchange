package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Other tests create public exchanges in the same database, so every test here searches for a name
 * prefix that only its own rows use. That keeps the expectations exact.
 */
class ExchangeListTest extends AbstractIntegrationTest {

    static final String TAG = "lst9z"; // appears only in this class's exchange names

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private long seq;

    @BeforeEach
    void clean() {
        jdbc.update("delete from book_submission where exchange_id in (select id from exchange where name ilike ?)", "%" + TAG + "%");
        jdbc.update("delete from exchange where name ilike ?", "%" + TAG + "%");
    }

    /** Inserts an exchange with a fixed creation time (minutes after a base time) and returns its code. */
    private String exchange(String name, boolean listed, String status, int minute) {
        String code = "LS" + (++seq) + Long.toString(System.nanoTime(), 36).toUpperCase();
        code = code.substring(0, Math.min(code.length(), 12));
        jdbc.update("insert into exchange (code, name, description, listed, status, created_at) values (?, ?, ?, ?, ?, ?)",
                code, name, "About " + name, listed, status,
                java.sql.Timestamp.from(Instant.parse("2026-11-01T10:00:00Z").plusSeconds(minute * 60L)));
        return code;
    }

    private void book(String code, String submitter, String claimedBy) {
        jdbc.update("insert into book_submission (exchange_id, submitter_name, book_title, book_url, wrapping_paper, "
                        + "claimed_by_name, claimed_at) values ((select id from exchange where code = ?), ?, 't', "
                        + "'https://www.amazon.com/dp/1', 'stars', ?, case when ?::text is null then null else now() end)",
                code, submitter, claimedBy, claimedBy);
    }

    private ResultActions list(String query) throws Exception {
        return mvc.perform(get("/api/exchanges").param("query", query));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("name").asText()));
        return out;
    }

    @Test
    void listsOnlyPublicExchangesAndNeverPrivateOnes() throws Exception {
        exchange(TAG + " public", true, "OPEN", 1);
        exchange(TAG + " private", false, "OPEN", 2);

        assertThat(names(body(list(TAG).andExpect(status().isOk())))).containsExactly(TAG + " public");
        // ...even when searching for the private one by its exact name
        assertThat(names(body(list(TAG + " private")))).isEmpty();
    }

    @Test
    void searchIsCaseInsensitiveAndMatchesAnywhereInTheName() throws Exception {
        exchange("Office " + TAG + " Party", true, "OPEN", 1);
        exchange("Family " + TAG, true, "OPEN", 2);

        assertThat(names(body(list("OFFICE " + TAG.toUpperCase())))).containsExactly("Office " + TAG + " Party");
        assertThat(names(body(list("party")))).contains("Office " + TAG + " Party");
        assertThat(names(body(list("  " + TAG + "  ")))).hasSize(2); // surrounding spaces ignored
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainCharacters() throws Exception {
        exchange(TAG + " 100% fun", true, "OPEN", 1);
        exchange(TAG + " 100 plain", true, "OPEN", 2);
        exchange(TAG + " a_b", true, "OPEN", 3);
        exchange(TAG + " axb", true, "OPEN", 4);

        assertThat(names(body(list("100% fun")))).containsExactly(TAG + " 100% fun");
        assertThat(names(body(list("%")))).containsExactly(TAG + " 100% fun");
        assertThat(names(body(list(TAG + " a_b")))).containsExactly(TAG + " a_b");
        assertThat(names(body(list("!")))).isEmpty();
    }

    @Test
    void openExchangesComeFirstThenClosedAndNewestFirstWithinEach() throws Exception {
        exchange(TAG + " closed-old", true, "CLOSED", 1);
        exchange(TAG + " open-old", true, "OPEN", 2);
        exchange(TAG + " closed-new", true, "CLOSED", 5);
        exchange(TAG + " open-new", true, "OPEN", 4);

        assertThat(names(body(list(TAG)))).containsExactly(
                TAG + " open-new", TAG + " open-old", TAG + " closed-new", TAG + " closed-old");
    }

    @Test
    void canBeFilteredByStatus() throws Exception {
        exchange(TAG + " open", true, "OPEN", 1);
        exchange(TAG + " closed", true, "CLOSED", 2);

        assertThat(names(body(mvc.perform(get("/api/exchanges").param("query", TAG).param("status", "OPEN")))))
                .containsExactly(TAG + " open");
        assertThat(names(body(mvc.perform(get("/api/exchanges").param("query", TAG).param("status", "CLOSED")))))
                .containsExactly(TAG + " closed");
    }

    @Test
    void eachItemShowsTheDocumentedFieldsAndCorrectCounts() throws Exception {
        String code = exchange(TAG + " counted", true, "OPEN", 1);
        book(code, "Zach", null);
        book(code, "Sam", "Alex");
        book(code, "Pat", "Lee");

        JsonNode item = body(list(TAG)).get("items").get(0);
        assertThat(item.get("code").asText()).isEqualTo(code);
        assertThat(item.get("name").asText()).isEqualTo(TAG + " counted");
        assertThat(item.get("description").asText()).isEqualTo("About " + TAG + " counted");
        assertThat(item.get("status").asText()).isEqualTo("OPEN");
        assertThat(item.get("bookCount").asLong()).isEqualTo(3);
        assertThat(item.get("claimedCount").asLong()).isEqualTo(2);
        assertThat(item.get("createdAt").asText()).startsWith("2026-11-01T10:01");

        // exactly these fields: nothing about the books or the organizer
        List<String> fields = new ArrayList<>();
        item.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "code", "name", "description", "status", "bookCount", "claimedCount", "createdAt");
        assertThat(item.toString()).doesNotContain("Zach", "Sam", "Alex", "amazon");
    }

    @Test
    void anExchangeWithNoBooksHasZeroCounts() throws Exception {
        exchange(TAG + " empty", true, "OPEN", 1);
        JsonNode item = body(list(TAG)).get("items").get(0);
        assertThat(item.get("bookCount").asLong()).isZero();
        assertThat(item.get("claimedCount").asLong()).isZero();
    }

    @Test
    void isPaginatedWithAStableOrderAndTotals() throws Exception {
        for (int i = 1; i <= 7; i++) exchange(TAG + " n" + i, true, "OPEN", i); // newest = n7

        JsonNode first = body(mvc.perform(get("/api/exchanges").param("query", TAG).param("size", "3")));
        JsonNode second = body(mvc.perform(get("/api/exchanges").param("query", TAG).param("size", "3").param("page", "1")));
        JsonNode third = body(mvc.perform(get("/api/exchanges").param("query", TAG).param("size", "3").param("page", "2")));

        assertThat(names(first)).containsExactly(TAG + " n7", TAG + " n6", TAG + " n5");
        assertThat(names(second)).containsExactly(TAG + " n4", TAG + " n3", TAG + " n2");
        assertThat(names(third)).containsExactly(TAG + " n1");
        assertThat(first.get("totalItems").asLong()).isEqualTo(7);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("size").asInt()).isEqualTo(3);
        assertThat(second.get("page").asInt()).isEqualTo(1);
    }

    @Test
    void pagesDoNotRepeatOrSkipEvenWhenCreationTimesAreEqual() throws Exception {
        for (int i = 1; i <= 5; i++) exchange(TAG + " same" + i, true, "OPEN", 1); // identical created_at

        List<String> all = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            all.addAll(names(body(mvc.perform(get("/api/exchanges").param("query", TAG).param("size", "2").param("page", "" + page)))));
        }
        assertThat(all).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void aPagePastTheEndIsEmptyAndNothingMatchingGivesAnEmptyList() throws Exception {
        exchange(TAG + " only", true, "OPEN", 1);
        mvc.perform(get("/api/exchanges").param("query", TAG).param("page", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(1));

        list("zzz-nothing-has-this-name").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void usesDefaultsWhenNoParametersAreGiven() throws Exception {
        mvc.perform(get("/api/exchanges")).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(12));
    }

    @Test
    void rejectsInvalidParameters() throws Exception {
        mvc.perform(get("/api/exchanges").param("page", "-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("page must be 0 or more"));
        mvc.perform(get("/api/exchanges").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/exchanges").param("size", "51")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("size must be between 1 and 50"));
        mvc.perform(get("/api/exchanges").param("query", "x".repeat(101))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/exchanges").param("status", "SOMETIMES")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/exchanges").param("page", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    void theSeededPublicExchangeIsListed() throws Exception {
        assertThat(names(body(list("christmas book exchange 2026")))).contains("Christmas Book Exchange 2026");
    }

    @Test
    void privateExchangesAreStillReachableByCode() throws Exception {
        String code = exchange(TAG + " hidden", false, "OPEN", 1);
        mvc.perform(get("/api/exchanges/{c}", code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.listed").value(false));
    }
}
