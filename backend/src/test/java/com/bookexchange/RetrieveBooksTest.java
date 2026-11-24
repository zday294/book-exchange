package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.domain.BookSubmission;
import com.bookexchange.domain.Exchange;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class RetrieveBooksTest extends AbstractIntegrationTest {

    static final String URL = "https://www.amazon.com/dp/0547928221";
    static final String COVER = "https://example.com/cover.jpg";

    @Autowired MockMvc mvc;
    @Autowired ExchangeRepository exchanges;
    @Autowired BookSubmissionRepository books;
    @Autowired JdbcTemplate jdbc;

    Exchange exchange;
    BookSubmission unclaimed;
    BookSubmission claimed;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from book_submission");
        exchange = exchanges.findByCode("RETRIEVE").orElseGet(() -> exchanges.save(new Exchange("RETRIEVE", "Retrieve")));
        unclaimed = books.save(new BookSubmission(exchange, "Zach", "Secret Title", URL, "candy-cane"));
        unclaimed.setCoverImageUrl(COVER);
        unclaimed.setCoverAspectRatio(new java.math.BigDecimal("0.67"));
        books.save(unclaimed);
        claimed = new BookSubmission(exchange, "Sam", "The Hobbit", URL, "holly");
        claimed.setCoverImageUrl(COVER);
        claimed.claim("Alex", Instant.parse("2026-12-01T18:02:11Z"));
        books.save(claimed);
    }

    private ResultActions getBook(BookSubmission b, String viewer) throws Exception {
        return perform(get("/api/exchanges/RETRIEVE/books/{id}", b.getId()), viewer);
    }

    private ResultActions perform(MockHttpServletRequestBuilder req, String viewer) throws Exception {
        if (viewer != null) req.header("X-Participant-Name", viewer);
        return mvc.perform(req);
    }

    private static void assertNoneOf(ResultActions r, String... fields) throws Exception {
        for (String f : fields) r.andExpect(jsonPath("$." + f).doesNotExist());
    }

    // ---- Visibility table: every cell, on the single-book endpoint ----

    @Test
    void unclaimedBookRevealsNothingToAnonymousViewers() throws Exception {
        ResultActions r = getBook(unclaimed, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(unclaimed.getId()))
                .andExpect(jsonPath("$.submitterName").value("Zach"))
                .andExpect(jsonPath("$.wrappingPaper").value("candy-cane"))
                .andExpect(jsonPath("$.claimed").value(false))
                .andExpect(jsonPath("$.viewerIsClaimer").value(false))
                .andExpect(jsonPath("$.coverAspectRatio").value(0.67)); // the one cover fact that is public
        assertNoneOf(r, "bookTitle", "coverImageUrl", "amazonUrl", "claimedByName", "claimedAt");
    }

    @Test
    void anUnclaimedBookExposesExactlyTheRatioAndNothingElseAboutTheCover() throws Exception {
        for (String viewer : new String[] {null, "Zach", "Alex"}) {
            String body = getBook(unclaimed, viewer).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrder("id", "submitterName", "wrappingPaper", "claimed", "viewerIsClaimer", "coverAspectRatio");
            assertThat(body).doesNotContain(COVER).doesNotContain("Secret Title").doesNotContain(URL)
                    .doesNotContain("idth").doesNotContain("eight"); // no pixel sizes
        }
        // and in the list (first element is the unclaimed book; the second is claimed and may show its cover)
        String list = perform(get("/api/exchanges/{c}/books", exchange.getCode()), "Alex").andReturn().getResponse().getContentAsString();
        var first = new com.fasterxml.jackson.databind.ObjectMapper().readTree(list).get(0);
        assertThat(first.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "submitterName", "wrappingPaper", "claimed", "viewerIsClaimer", "coverAspectRatio");
        assertThat(first.get("coverAspectRatio").decimalValue()).isEqualByComparingTo("0.67");
    }

    @Test
    void theRatioIsOmittedWhenNoCoverIsKnown() throws Exception {
        unclaimed.setCoverAspectRatio(null);
        books.save(unclaimed);
        assertNoneOf(getBook(unclaimed, null).andExpect(status().isOk()), "coverAspectRatio");
    }

    @Test
    void unclaimedBookRevealsNothingEvenToTheSubmitterOrAnyName() throws Exception {
        for (String viewer : new String[] {"Zach", "Alex", ""}) {
            assertNoneOf(getBook(unclaimed, viewer).andExpect(status().isOk()),
                    "bookTitle", "coverImageUrl", "amazonUrl", "claimedByName", "claimedAt");
        }
    }

    @Test
    void claimedBookShowsTitleCoverAndClaimerButNotTheLinkToOthers() throws Exception {
        for (String viewer : new String[] {null, "Zach", "Someone Else"}) {
            ResultActions r = getBook(claimed, viewer).andExpect(status().isOk())
                    .andExpect(jsonPath("$.claimed").value(true))
                    .andExpect(jsonPath("$.viewerIsClaimer").value(false))
                    .andExpect(jsonPath("$.bookTitle").value("The Hobbit"))
                    .andExpect(jsonPath("$.coverImageUrl").value(COVER))
                    .andExpect(jsonPath("$.claimedByName").value("Alex"))
                    .andExpect(jsonPath("$.claimedAt").value("2026-12-01T18:02:11Z"));
            assertNoneOf(r, "amazonUrl");
        }
    }

    @Test
    void claimerAlsoSeesTheAmazonLink() throws Exception {
        getBook(claimed, "Alex").andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerIsClaimer").value(true))
                .andExpect(jsonPath("$.amazonUrl").value(URL))
                .andExpect(jsonPath("$.bookTitle").value("The Hobbit"))
                .andExpect(jsonPath("$.coverImageUrl").value(COVER));
    }

    @Test
    void claimerMatchingIgnoresCaseAndSurroundingSpaces() throws Exception {
        getBook(claimed, "  aLEX ").andExpect(jsonPath("$.amazonUrl").value(URL))
                .andExpect(jsonPath("$.viewerIsClaimer").value(true));
    }

    @Test
    void claimedBookWithoutCoverOmitsTheCoverField() throws Exception {
        claimed.setCoverImageUrl(null);
        books.save(claimed);
        assertNoneOf(getBook(claimed, null).andExpect(status().isOk()), "coverImageUrl");
    }

    // ---- List endpoint ----

    @Test
    void listReturnsEveryBookInStableOrderWithPerBookVisibility() throws Exception {
        perform(get("/api/exchanges/RETRIEVE/books"), "Alex").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(unclaimed.getId()))
                .andExpect(jsonPath("$[0].bookTitle").doesNotExist())
                .andExpect(jsonPath("$[0].amazonUrl").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(claimed.getId()))
                .andExpect(jsonPath("$[1].amazonUrl").value(URL));
    }

    @Test
    void listNeverLeaksHiddenDataToOtherViewers() throws Exception {
        String body = perform(get("/api/exchanges/RETRIEVE/books"), "Zach").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("Secret Title", URL).contains("The Hobbit");
    }

    @Test
    void listOnlyIncludesBooksFromThatExchange() throws Exception {
        Exchange other = exchanges.findByCode("RETOTHER").orElseGet(() -> exchanges.save(new Exchange("RETOTHER", "Other")));
        books.save(new BookSubmission(other, "Zed", "Elsewhere", URL, "stars"));
        perform(get("/api/exchanges/RETRIEVE/books"), null).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void emptyExchangeReturnsAnEmptyList() throws Exception {
        exchanges.findByCode("RETEMPTY").orElseGet(() -> exchanges.save(new Exchange("RETEMPTY", "Empty")));
        perform(get("/api/exchanges/RETEMPTY/books"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---- 404s ----

    @Test
    void unknownExchangeIs404ForListAndGet() throws Exception {
        perform(get("/api/exchanges/NOPE/books"), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Exchange not found"));
        perform(get("/api/exchanges/NOPE/books/1"), null).andExpect(status().isNotFound());
    }

    @Test
    void unknownBookIs404() throws Exception {
        perform(get("/api/exchanges/RETRIEVE/books/999999"), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Book not found"));
    }

    @Test
    void bookFromAnotherExchangeIs404() throws Exception {
        Exchange other = exchanges.findByCode("RETOTHER").orElseGet(() -> exchanges.save(new Exchange("RETOTHER", "Other")));
        BookSubmission b = books.save(new BookSubmission(other, "Zed", "Elsewhere", URL, "stars"));
        perform(get("/api/exchanges/RETRIEVE/books/{id}", b.getId()), null).andExpect(status().isNotFound());
    }
}
