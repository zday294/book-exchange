package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.exchange.ExchangeModeChangedEvent;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.repository.ExchangeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(ExchangeModeTest.Capture.class)
class ExchangeModeTest extends AbstractIntegrationTest {

    static final List<ExchangeModeChangedEvent> EVENTS = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class Capture {
        @EventListener
        void on(ExchangeModeChangedEvent event) {
            EVENTS.add(event);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ExchangeRepository exchanges;
    @Autowired BookSubmissionRepository books;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager txManager;

    private record Created(String code, String token) {}

    @BeforeEach
    void clear() {
        EVENTS.clear();
    }

    private Created create(String name) throws Exception {
        var node = json.readTree(mvc.perform(post("/api/exchanges").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "organizerName", "Host"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Created(node.get("code").asText(), node.get("hostToken").asText());
    }

    private ResultActions setMode(String code, String token, String body) throws Exception {
        var request = put("/api/exchanges/{c}/mode", code).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) request.header("X-Host-Token", token);
        return mvc.perform(request);
    }

    private ResultActions setMode(Created c, String mode) throws Exception {
        return setMode(c.code(), c.token(), "{\"mode\":\"" + mode + "\"}");
    }

    private ResultActions submit(String code, String who) throws Exception {
        return mvc.perform(post("/api/exchanges/{c}/books", code).contentType(MediaType.APPLICATION_JSON)
                .content("{\"submitterName\":\"" + who + "\",\"bookTitle\":\"The Hobbit\","
                        + "\"amazonUrl\":\"https://www.amazon.com/dp/0547928221\",\"wrappingPaper\":\"holly\"}"));
    }

    private ResultActions claim(String code, long bookId, String who) throws Exception {
        return mvc.perform(post("/api/exchanges/{c}/books/{id}/claim", code, bookId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"participantName\":\"" + who + "\"}"));
    }

    private long submitAndGetId(String code, String who) throws Exception {
        return json.readTree(submit(code, who).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asLong();
    }

    private long bookCount(String code) {
        return jdbc.queryForObject("select count(*) from book_submission where exchange_id = "
                + "(select id from exchange where code = ?)", Long.class, code);
    }

    // ---- the two modes ----

    @Test
    void aNewExchangeStartsInSubmitModeSoSubmittingWorksAndClaimingDoesNot() throws Exception {
        Created c = create("Fresh");
        mvc.perform(get("/api/exchanges/{c}", c.code())).andExpect(jsonPath("$.mode").value("SUBMIT"));

        long id = submitAndGetId(c.code(), "Sam");
        claim(c.code(), id, "Alex").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Claiming hasn't started yet")));
        assertThat(jdbc.queryForObject("select claimed_by_name from book_submission where id = ?", String.class, id)).isNull();
    }

    @Test
    void createResponseAlreadyShowsTheMode() throws Exception {
        mvc.perform(post("/api/exchanges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Shown\",\"organizerName\":\"Host\"}"))
                .andExpect(jsonPath("$.mode").value("SUBMIT"));
    }

    @Test
    void inClaimModeClaimingWorksAndSubmittingDoesNotAndSavesNothing() throws Exception {
        Created c = create("Claiming");
        long id = submitAndGetId(c.code(), "Sam");
        setMode(c, "CLAIM").andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("CLAIM"))
                .andExpect(jsonPath("$.isHost").value(true));

        long before = bookCount(c.code());
        submit(c.code(), "Late Larry").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("moved on to claiming")));
        assertThat(bookCount(c.code())).isEqualTo(before);

        claim(c.code(), id, "Alex").andExpect(status().isOk()).andExpect(jsonPath("$.claimedByName").value("Alex"));
    }

    @Test
    void theHostCanSwitchBackAndForthAndNothingExistingIsTouched() throws Exception {
        Created c = create("Back and forth");
        long first = submitAndGetId(c.code(), "Sam");
        submitAndGetId(c.code(), "Pat");
        setMode(c, "CLAIM").andExpect(status().isOk());
        claim(c.code(), first, "Alex").andExpect(status().isOk());

        setMode(c, "SUBMIT").andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("SUBMIT"));
        assertThat(bookCount(c.code())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select claimed_by_name from book_submission where id = ?", String.class, first))
                .isEqualTo("Alex"); // the claim survived the switch
        submit(c.code(), "Forgetful Fran").andExpect(status().isCreated()); // someone who forgot their book
        claim(c.code(), first, "Lee").andExpect(status().isConflict()); // claiming paused

        setMode(c, "CLAIM").andExpect(status().isOk());
        assertThat(bookCount(c.code())).isEqualTo(3);
    }

    @Test
    void readingThePileWorksInBothModes() throws Exception {
        Created c = create("Readable");
        submitAndGetId(c.code(), "Sam");
        mvc.perform(get("/api/exchanges/{c}/books", c.code())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        setMode(c, "CLAIM").andExpect(status().isOk());
        mvc.perform(get("/api/exchanges/{c}/books", c.code())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aClosedExchangeAcceptsNeitherWhateverTheMode() throws Exception {
        Created c = create("Finished");
        long id = submitAndGetId(c.code(), "Sam");
        for (String mode : new String[] {"SUBMIT", "CLAIM"}) {
            setMode(c, mode).andExpect(status().isOk());
            jdbc.update("update exchange set status = 'CLOSED' where code = ?", c.code());
            submit(c.code(), "Nobody" + mode).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("closed")));
            claim(c.code(), id, "Nobody" + mode).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("closed")));
            jdbc.update("update exchange set status = 'OPEN' where code = ?", c.code());
        }
        assertThat(bookCount(c.code())).isEqualTo(1);
    }

    @Test
    void theModeIsInTheExchangeDetailsAndOtherUpdates() throws Exception {
        Created c = create("Details");
        setMode(c, "CLAIM").andExpect(status().isOk());
        mvc.perform(get("/api/exchanges/{c}", c.code())).andExpect(jsonPath("$.mode").value("CLAIM"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/exchanges/{c}", c.code())
                        .header("X-Host-Token", c.token()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed\"}"))
                .andExpect(jsonPath("$.mode").value("CLAIM")); // editing settings doesn't touch the mode
    }

    // ---- who may switch ----

    @Test
    void onlyTheHostCanSwitch() throws Exception {
        Created mine = create("Mine");
        Created other = create("Other");
        String body = "{\"mode\":\"CLAIM\"}";

        setMode(mine.code(), null, body).andExpect(status().isForbidden());
        setMode(mine.code(), "wrong", body).andExpect(status().isForbidden());
        setMode(mine.code(), other.token(), body).andExpect(status().isForbidden());
        exchanges.save(new Exchange("NOHOST33", "No host"));
        setMode("NOHOST33", "anything", body).andExpect(status().isForbidden());
        setMode("XMAS2026", "anything", body).andExpect(status().isForbidden());

        mvc.perform(get("/api/exchanges/{c}", mine.code())).andExpect(jsonPath("$.mode").value("SUBMIT"));
        assertThat(EVENTS).isEmpty();
    }

    @Test
    void unknownExchangeIs404AndBadModesAre400() throws Exception {
        Created c = create("Validated");
        setMode("NOPE123", "token", "{\"mode\":\"CLAIM\"}").andExpect(status().isNotFound());
        setMode(c.code(), c.token(), "{\"mode\":\"PARTY\"}").andExpect(status().isBadRequest());
        setMode(c.code(), c.token(), "{\"mode\":\"claim\"}").andExpect(status().isBadRequest());
        setMode(c.code(), c.token(), "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.startsWith("mode:")));
        setMode(c.code(), c.token(), "{not json").andExpect(status().isBadRequest());
        mvc.perform(get("/api/exchanges/{c}", c.code())).andExpect(jsonPath("$.mode").value("SUBMIT"));
    }

    // ---- events ----

    @Test
    void aChangePublishesAnEventAndRepeatingTheSameModeDoesNot() throws Exception {
        Created c = create("Events");
        setMode(c, "CLAIM").andExpect(status().isOk());
        setMode(c, "CLAIM").andExpect(status().isOk());
        setMode(c, "SUBMIT").andExpect(status().isOk());

        assertThat(EVENTS).extracting(ExchangeModeChangedEvent::mode).extracting(Enum::name).containsExactly("CLAIM", "SUBMIT");
        assertThat(EVENTS).allMatch(e -> e.exchangeCode().equals(c.code()));
    }

    // ---- races: the exchange row lock makes a switch and a submit/claim happen one after the other ----

    private void waitUntilSomeoneIsWaitingForALock() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Long waiting = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()", Long.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("nothing ever waited for the exchange lock");
    }

    @Test
    void aSubmitThatIsWaitingForAModeSwitchSeesTheNewModeAndIsRejected() throws Exception {
        Created c = create("Race submit");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Integer>> submitting = new AtomicReference<>();

        // The host's switch is in progress (holds the exchange row) when the submit arrives.
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            jdbc.queryForObject("select id from exchange where code = ? for update", Long.class, c.code());
            submitting.set(pool.submit(() -> submit(c.code(), "Racer").andReturn().getResponse().getStatus()));
            try {
                waitUntilSomeoneIsWaitingForALock(); // the submit is now blocked behind the switch
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            jdbc.update("update exchange set mode = 'CLAIM' where code = ?", c.code());
        });

        assertThat(submitting.get().get(10, TimeUnit.SECONDS)).isEqualTo(409);
        assertThat(bookCount(c.code())).isZero(); // no book slipped in after claiming began
        pool.shutdown();
    }

    @Test
    void aClaimThatIsWaitingForAModeSwitchSeesTheNewModeAndIsRejected() throws Exception {
        Created c = create("Race claim");
        long id = submitAndGetId(c.code(), "Sam");
        setMode(c, "CLAIM").andExpect(status().isOk());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Integer>> claiming = new AtomicReference<>();

        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            jdbc.queryForObject("select id from exchange where code = ? for update", Long.class, c.code());
            claiming.set(pool.submit(() -> claim(c.code(), id, "Alex").andReturn().getResponse().getStatus()));
            try {
                waitUntilSomeoneIsWaitingForALock();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            jdbc.update("update exchange set mode = 'SUBMIT' where code = ?", c.code()); // the host paused claiming
        });

        assertThat(claiming.get().get(10, TimeUnit.SECONDS)).isEqualTo(409);
        assertThat(jdbc.queryForObject("select claimed_by_name from book_submission where id = ?", String.class, id)).isNull();
        pool.shutdown();
    }

    @Test
    void submissionsInProgressDoNotBlockEachOtherOrBreak() throws Exception {
        Created c = create("Many at once");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String who = "Person" + i;
            results.add(pool.submit(() -> submit(c.code(), who).andReturn().getResponse().getStatus()));
        }
        for (Future<Integer> f : results) assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        assertThat(bookCount(c.code())).isEqualTo(12);
        pool.shutdown();
    }
}
