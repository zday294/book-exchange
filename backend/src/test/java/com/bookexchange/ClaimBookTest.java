package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.book.BookClaimedEvent;
import com.bookexchange.domain.BookSubmission;
import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeMode;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Import(ClaimBookTest.EventCapture.class)
class ClaimBookTest extends AbstractIntegrationTest {

    static final String URL = "https://www.amazon.com/dp/0547928221";
    static final List<BookClaimedEvent> EVENTS = new java.util.concurrent.CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class EventCapture {
        @EventListener
        void on(BookClaimedEvent e) {
            EVENTS.add(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ExchangeRepository exchanges;
    @Autowired BookSubmissionRepository books;
    @Autowired JdbcTemplate jdbc;

    Exchange exchange;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from book_submission");
        EVENTS.clear();
        exchange = claimingExchange("CLAIMEX", "Claim");
    }

    /** An exchange that is in claim mode (claiming is only allowed there). */
    private Exchange claimingExchange(String code, String name) {
        Exchange e = exchanges.findByCode(code).orElseGet(() -> new Exchange(code, name));
        e.setMode(ExchangeMode.CLAIM);
        e.setStatus(ExchangeStatus.OPEN);
        return exchanges.save(e);
    }

    private BookSubmission book(String submitter, String title) {
        BookSubmission b = new BookSubmission(exchange, submitter, title, URL, "candy-cane");
        b.setCoverImageUrl("https://example.com/cover.jpg");
        return books.save(b);
    }

    private ResultActions claim(String code, Object bookId, String body) throws Exception {
        return mvc.perform(post("/api/exchanges/{c}/books/{id}/claim", code, bookId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions claim(BookSubmission b, String name) throws Exception {
        return claim("CLAIMEX", b.getId(), "{\"participantName\":\"" + name + "\"}");
    }

    private BookSubmission reload(BookSubmission b) {
        return books.findById(b.getId()).orElseThrow();
    }

    @Test
    void successfulClaimReturnsTheClaimersViewWithTheLink() throws Exception {
        BookSubmission b = book("Zach", "The Hobbit");
        claim(b, "Alex").andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(true))
                .andExpect(jsonPath("$.viewerIsClaimer").value(true))
                .andExpect(jsonPath("$.claimedByName").value("Alex"))
                .andExpect(jsonPath("$.claimedAt").isNotEmpty())
                .andExpect(jsonPath("$.bookTitle").value("The Hobbit"))
                .andExpect(jsonPath("$.coverImageUrl").value("https://example.com/cover.jpg"))
                .andExpect(jsonPath("$.bookUrl").value(URL));

        BookSubmission saved = reload(b);
        assertThat(saved.getClaimedByName()).isEqualTo("Alex");
        assertThat(saved.getClaimedAt()).isNotNull();
    }

    @Test
    void aBarnesAndNobleLinkIsHiddenUntilClaimedAndThenOnlyTheClaimerGetsIt() throws Exception {
        String bn = "https://www.barnesandnoble.com/w/the-hobbit-j-r-r-tolkien/1100054405?ean=9780547928227";
        BookSubmission b = new BookSubmission(exchange, "Zach", "The Hobbit", bn, "candy-cane");
        b = books.save(b);

        mvc.perform(get("/api/exchanges/CLAIMEX/books/{id}", b.getId()).header("X-Participant-Name", "Alex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(false))
                .andExpect(jsonPath("$.bookUrl").doesNotExist());

        claim(b, "Alex").andExpect(status().isOk()).andExpect(jsonPath("$.bookUrl").value(bn));

        mvc.perform(get("/api/exchanges/CLAIMEX/books/{id}", b.getId()).header("X-Participant-Name", "Sam"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(true))
                .andExpect(jsonPath("$.bookUrl").doesNotExist());
        mvc.perform(get("/api/exchanges/CLAIMEX/books/{id}", b.getId()))
                .andExpect(jsonPath("$.bookUrl").doesNotExist());
    }

    @Test
    void nameIsTrimmedBeforeSaving() throws Exception {
        BookSubmission b = book("Zach", "The Hobbit");
        claim(b, "  Alex  ").andExpect(status().isOk()).andExpect(jsonPath("$.claimedByName").value("Alex"));
    }

    @Test
    void claimedBookCannotBeClaimedAgain() throws Exception {
        BookSubmission b = book("Zach", "The Hobbit");
        claim(b, "Alex").andExpect(status().isOk());
        claim(b, "Sam").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This book has already been claimed"));
        assertThat(reload(b).getClaimedByName()).isEqualTo("Alex");
    }

    @Test
    void personCannotClaimASecondBookEvenWithDifferentCase() throws Exception {
        BookSubmission one = book("Zach", "One");
        BookSubmission two = book("Sam", "Two");
        claim(one, "Alex").andExpect(status().isOk());
        claim(two, "ALEX").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("You have already claimed a book in this exchange"));
        assertThat(reload(two).isClaimed()).isFalse();
    }

    @Test
    void personCannotClaimTheirOwnBookCaseInsensitively() throws Exception {
        BookSubmission b = book("Zach", "Mine");
        claim(b, "zACH").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("You can't claim a book you submitted yourself"));
        assertThat(reload(b).isClaimed()).isFalse();
    }

    @Test
    void closedExchangeRejectsClaims() throws Exception {
        BookSubmission b = book("Zach", "The Hobbit");
        exchange.setStatus(ExchangeStatus.CLOSED);
        exchanges.save(exchange);
        try {
            claim(b, "Alex").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("closed")));
            assertThat(reload(b).isClaimed()).isFalse();
        } finally {
            exchange.setStatus(ExchangeStatus.OPEN);
            exchanges.save(exchange);
        }
    }

    @Test
    void unknownExchangeAndBookAre404() throws Exception {
        BookSubmission b = book("Zach", "The Hobbit");
        claim("NOPE", b.getId(), "{\"participantName\":\"Alex\"}").andExpect(status().isNotFound());
        claim("CLAIMEX", 999999, "{\"participantName\":\"Alex\"}").andExpect(status().isNotFound());
    }

    @Test
    void bookFromAnotherExchangeIs404() throws Exception {
        Exchange other = claimingExchange("CLAIMOTH", "O");
        BookSubmission b = books.save(new BookSubmission(other, "Zed", "Elsewhere", URL, "stars"));
        claim("CLAIMEX", b.getId(), "{\"participantName\":\"Alex\"}").andExpect(status().isNotFound());
    }

    @Test
    void invalidNameIs400AndSavesNothing() throws Exception {
        BookSubmission b = book("Zach", "The Hobbit");
        claim("CLAIMEX", b.getId(), "{\"participantName\":\"   \"}").andExpect(status().isBadRequest());
        claim("CLAIMEX", b.getId(), "{}").andExpect(status().isBadRequest());
        claim("CLAIMEX", b.getId(), "{\"participantName\":\"" + "x".repeat(101) + "\"}")
                .andExpect(status().isBadRequest());
        assertThat(reload(b).isClaimed()).isFalse();
    }

    @Test
    void sameNameCanClaimInAnotherExchange() throws Exception {
        Exchange other = claimingExchange("CLAIMOTH", "O");
        BookSubmission here = book("Zach", "Here");
        BookSubmission there = books.save(new BookSubmission(other, "Zed", "There", URL, "stars"));
        claim(here, "Alex").andExpect(status().isOk());
        claim("CLAIMOTH", there.getId(), "{\"participantName\":\"Alex\"}").andExpect(status().isOk());
    }

    @Test
    void claimPublishesAPublicEventWithoutTheAmazonLink() throws Exception {
        BookSubmission b = book("Zach", "The Hobbit");
        claim(b, "Alex").andExpect(status().isOk());

        assertThat(EVENTS).hasSize(1);
        BookClaimedEvent e = EVENTS.get(0);
        assertThat(e.bookId()).isEqualTo(b.getId());
        assertThat(e.bookTitle()).isEqualTo("The Hobbit");
        assertThat(e.claimedByName()).isEqualTo("Alex");
        assertThat(e.claimedAt()).isNotNull();
        assertThat(e.toString()).doesNotContain(URL);
        assertThat(java.util.Arrays.stream(BookClaimedEvent.class.getRecordComponents())
                .map(c -> c.getName().toLowerCase())).noneMatch(n -> n.contains("url") && n.contains("amazon"));
    }

    @Test
    void rejectedClaimsPublishNoEvent() throws Exception {
        BookSubmission b = book("Zach", "Mine");
        claim(b, "Zach").andExpect(status().isConflict());
        assertThat(EVENTS).isEmpty();
    }

    // ---- Concurrency ----

    /** Runs the tasks at the same moment and returns each HTTP status. */
    private List<Integer> race(List<Supplier<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Supplier<Integer> t : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return t.get();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) statuses.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        return statuses;
    }

    private int statusOf(BookSubmission b, String name) {
        try {
            return claim(b, name).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parallelClaimsOnOneBookYieldASingle200() throws Exception {
        BookSubmission b = book("Zach", "Contested");
        List<Supplier<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = "Racer" + i;
            tasks.add(() -> statusOf(b, name));
        }
        List<Integer> statuses = race(tasks);

        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(7);
        assertThat(jdbc.queryForObject(
                "select count(*) from book_submission where claimed_by_name is not null", Integer.class)).isEqualTo(1);
        assertThat(EVENTS).hasSize(1);
    }

    @Test
    void parallelClaimsByOnePersonOnDifferentBooksYieldASingle200() throws Exception {
        List<BookSubmission> bs = new ArrayList<>();
        for (int i = 0; i < 8; i++) bs.add(book("Giver" + i, "Book " + i));
        List<Supplier<Integer>> tasks = new ArrayList<>();
        for (BookSubmission b : bs) tasks.add(() -> statusOf(b, "Greedy"));
        List<Integer> statuses = race(tasks);

        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(7);
        assertThat(jdbc.queryForObject(
                "select count(*) from book_submission where lower(claimed_by_name) = 'greedy'", Integer.class))
                .isEqualTo(1);
    }
}
