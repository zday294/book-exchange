package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.cover.CoverHttp;
import com.bookexchange.cover.CoverLookup;
import com.bookexchange.domain.BookSubmission;
import com.bookexchange.domain.Exchange;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The real lookup, with the network stubbed out: what it stores, and that it never gets in the way. */
@TestPropertySource(properties = "app.cover.enabled=true")
class CoverLookupTest extends AbstractIntegrationTest {

    private static final String OL = "https://covers.openlibrary.org/b/isbn/054792822X-L.jpg";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CoverLookup lookup;
    @Autowired ExchangeRepository exchanges;
    @Autowired BookSubmissionRepository books;
    @MockitoBean CoverHttp http;

    @BeforeEach
    void reset() {
        jdbc.update("delete from book_submission");
        Mockito.reset(http);
    }

    private long submit(String name, String link) throws Exception {
        String body = json.writeValueAsString(Map.of("submitterName", name, "bookTitle", "T-" + name,
                "bookUrl", link, "wrappingPaper", "candy-cane"));
        String response = mvc.perform(post("/api/exchanges/XMAS2026/books").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("select asin, cover_image_url from book_submission where id = ?", id);
    }

    @Test
    void anIsbnLinkGetsItsCoverAfterTheSubmission() throws Exception {
        when(http.fetchImage(URI.create(OL + "?default=false"))).thenReturn(Optional.of(new byte[] {1}));

        long id = submit("Zach", "https://www.amazon.com/dp/054792822X");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(row(id).get("cover_image_url")).isEqualTo(OL);
            assertThat(row(id).get("asin")).isEqualTo("054792822X");
        });
    }

    @Test
    void aBarnesAndNobleLinkIsSavedWithoutAnyLookupForNow() throws Exception {
        long id = submit("Zach", "https://www.barnesandnoble.com/w/the-hobbit/1100054405?ean=9780547928227");

        Thread.sleep(500); // give the asynchronous lookup time to run
        assertThat(row(id).get("cover_image_url")).isNull();
        verifyNoInteractions(http); // not even a redirect is followed for another store's link
    }

    private static byte[] png(int width, int height) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void theCoversProportionsAreStoredRoundedAndNeverItsPixelSize() throws Exception {
        when(http.fetchImage(any())).thenReturn(Optional.of(png(200, 300)));

        long id = submit("Zach", "https://www.amazon.com/dp/054792822X");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select cover_aspect_ratio from book_submission where id = ?", java.math.BigDecimal.class, id))
                        .isEqualByComparingTo("0.67"));
    }

    @Test
    void anImageThatCantBeMeasuredStillGivesACoverJustNoRatio() throws Exception {
        when(http.fetchImage(any())).thenReturn(Optional.of(new byte[] {1, 2, 3})); // not a picture

        long id = submit("Zach", "https://www.amazon.com/dp/054792822X");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(row(id).get("cover_image_url")).isEqualTo(OL));
        assertThat(jdbc.queryForObject("select cover_aspect_ratio from book_submission where id = ?", java.math.BigDecimal.class, id)).isNull();
    }

    @Test
    void aShortLinkIsFollowedToFindTheBook() throws Exception {
        when(http.resolveShortLink(URI.create("https://a.co/d/abc123")))
                .thenReturn(Optional.of(URI.create("https://www.amazon.com/Hobbit/dp/054792822X?ref=x")));
        when(http.fetchImage(any())).thenReturn(Optional.of(new byte[] {1}));

        long id = submit("Zach", "https://a.co/d/abc123");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(row(id).get("cover_image_url")).isEqualTo(OL));
        assertThat(row(id).get("asin")).isEqualTo("054792822X");
    }

    @Test
    void aNonBookProductKeepsItsAsinButGetsNoCoverAndNothingIsFetched() throws Exception {
        long id = submit("Zach", "https://www.amazon.com/dp/B08N5WRWNW");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(row(id).get("asin")).isEqualTo("B08N5WRWNW"));
        assertThat(row(id).get("cover_image_url")).isNull();
        verify(http, never()).fetchImage(any());
    }

    @Test
    void noCoverAtTheSourceLeavesTheCoverEmpty() throws Exception {
        when(http.fetchImage(any())).thenReturn(Optional.empty());

        long id = submit("Zach", "https://www.amazon.com/dp/054792822X");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(row(id).get("asin")).isEqualTo("054792822X"));
        assertThat(row(id).get("cover_image_url")).isNull();
    }

    @Test
    void anUnresolvableShortLinkChangesNothing() throws Exception {
        when(http.resolveShortLink(any())).thenReturn(Optional.empty());

        long id = submit("Zach", "https://amzn.to/3xYz");

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(row(id).get("asin")).isNull();
            assertThat(row(id).get("cover_image_url")).isNull();
        });
    }

    @Test
    void aFailingSourceNeverFailsTheSubmission() throws Exception {
        when(http.fetchImage(any())).thenThrow(new IllegalStateException("source down"));

        long id = submit("Zach", "https://www.amazon.com/dp/054792822X"); // still 201

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(row(id).get("cover_image_url")).isNull());
    }

    @Test
    void aSlowSourceDoesNotSlowDownTheSubmission() throws Exception {
        when(http.fetchImage(any())).thenAnswer(inv -> {
            Thread.sleep(2000);
            return Optional.of(new byte[] {1});
        });

        long start = System.nanoTime();
        long id = submit("Zach", "https://www.amazon.com/dp/054792822X");
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).isLessThan(1500);
        await().atMost(Duration.ofSeconds(6)).untilAsserted(() -> assertThat(row(id).get("cover_image_url")).isEqualTo(OL));
    }

    @Test
    void aLinkOutsideTheAllowListIsNeverFetched() {
        // Can't get in through the API (validation rejects it), so put it straight into the database.
        Exchange exchange = exchanges.findByCode("XMAS2026").orElseThrow();
        BookSubmission sneaky = books.save(new BookSubmission(exchange, "Eve", "Sneaky",
                "https://internal.example/dp/054792822X", "candy-cane"));

        lookup.resolveCover(sneaky.getId());

        verifyNoInteractions(http);
        assertThat(row(sneaky.getId()).get("asin")).isNull();
    }

    @Test
    void theCoverFoundIsShownToEveryoneOnceTheBookIsClaimed() throws Exception {
        when(http.fetchImage(any())).thenReturn(Optional.of(new byte[] {1}));
        long id = submit("Zach", "https://www.amazon.com/dp/054792822X");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(row(id).get("cover_image_url")).isEqualTo(OL));

        jdbc.update("update exchange set mode = 'CLAIM' where code = 'XMAS2026'");
        try {
            claimedBookShowsTheCover(id);
        } finally {
            jdbc.update("update exchange set mode = 'SUBMIT' where code = 'XMAS2026'");
        }
    }

    private void claimedBookShowsTheCover(long id) throws Exception {
        mvc.perform(get("/api/exchanges/XMAS2026/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].coverImageUrl").doesNotExist()); // hidden while unclaimed

        mvc.perform(post("/api/exchanges/XMAS2026/books/{id}/claim", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantName\":\"Alex\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverImageUrl").value(OL));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder get(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
    }
}
