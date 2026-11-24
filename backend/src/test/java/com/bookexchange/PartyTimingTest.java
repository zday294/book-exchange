package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.party.PartyService;
import com.bookexchange.support.PostgresTestConfig;
import com.bookexchange.support.SseTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The party's clock, made exact: the automatic timer is switched off (it only runs every ten minutes), the end
 * time of a turn is set directly in the database, and the timer's work is called by hand. So these tests say
 * precisely what happens at, before and after the end of a turn, without waiting for real time to pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.party.turn-seconds=120", "app.party.offline-grace-seconds=60", "app.party.tick-millis=600000",
        "app.events.heartbeat-millis=200"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class PartyTimingTest {

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PartyService party;

    private final List<SseTestClient> clients = new ArrayList<>();

    @AfterEach
    void goOffline() {
        clients.forEach(SseTestClient::close);
        clients.clear();
    }

    private record Exchange(String code, String token) {}

    private Exchange newExchange(String name, String... submitters) throws Exception {
        JsonNode n = json.readTree(mvc.perform(post("/api/exchanges").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "organizerName", "Host"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        Exchange e = new Exchange(n.get("code").asText(), n.get("hostToken").asText());
        for (String s : submitters) {
            mvc.perform(post("/api/exchanges/{c}/books", e.code()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"submitterName\":\"" + s + "\",\"bookTitle\":\"T\",\"amazonUrl\":\"https://www.amazon.com/dp/1\","
                            + "\"wrappingPaper\":\"holly\"}")).andExpect(status().isCreated());
        }
        return e;
    }

    private void online(Exchange e, String... names) throws Exception {
        for (String name : names) clients.add(new SseTestClient(port, e.code(), name).readyOrFail());
    }

    private JsonNode start(Exchange e) throws Exception {
        return json.readTree(mvc.perform(post("/api/exchanges/{c}/party/start", e.code()).header("X-Host-Token", e.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode party(Exchange e) throws Exception {
        return json.readTree(mvc.perform(get("/api/exchanges/{c}/party", e.code())).andReturn().getResponse().getContentAsString());
    }

    private ResultActions claim(Exchange e, long bookId, String who) throws Exception {
        return mvc.perform(post("/api/exchanges/{c}/books/{id}/claim", e.code(), bookId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"participantName\":\"" + who + "\"}"));
    }

    private long firstBookNotBy(Exchange e, String who) {
        return jdbc.queryForObject("select id from book_submission where exchange_id = (select id from exchange where code = ?) "
                + "and claimed_by_name is null and lower(submitter_name) <> lower(?) order by id limit 1", Long.class, e.code(), who);
    }

    /** Sets when the current turn ends: a negative number of milliseconds is in the past. */
    private void setTurnEnd(Exchange e, long millisFromNow) {
        jdbc.update("update party_turn set turn_ends_at = now() + make_interval(secs => ?) where status = 'CURRENT' "
                + "and exchange_id = (select id from exchange where code = ?)", millisFromNow / 1000.0, e.code());
    }

    private long count(Exchange e, String status) {
        return jdbc.queryForObject("select count(*) from party_turn where status = ? and exchange_id = "
                + "(select id from exchange where code = ?)", Long.class, status, e.code());
    }

    private List<String> statuses(JsonNode party) {
        List<String> out = new ArrayList<>();
        party.get("queue").forEach(q -> out.add(q.get("name").asText() + ":" + q.get("status").asText()));
        return out;
    }

    // ---- at and after the end of a turn ----

    @Test
    void aClaimAfterTheEndOfTheTurnIsRejectedEvenThoughTheTimerHasNotRunYet() throws Exception {
        Exchange e = newExchange("After the end", "Zed", "Yan", "Xia");
        online(e, "Amy", "Bob", "Cat");
        String current = start(e).get("current").asText();
        setTurnEnd(e, -1000); // the time is up, but nothing has noticed

        claim(e, firstBookNotBy(e, current), current).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("just ended")));

        assertThat(jdbc.queryForObject("select count(*) from book_submission where claimed_by_name is not null "
                + "and exchange_id = (select id from exchange where code = ?)", Integer.class, e.code())).isZero();
        assertThat(count(e, "CURRENT")).isEqualTo(1); // the timer hasn't run: same person, nothing changed
        assertThat(party(e).get("current").asText()).isEqualTo(current);

        party.tick(e.code()); // now the timer runs
        JsonNode after = party(e);
        assertThat(after.get("current").asText()).isNotEqualTo(current);
        assertThat(statuses(after)).contains(current + ":WAITING");
    }

    @Test
    void aClaimJustBeforeTheEndSucceedsAndTheTurnAdvancesExactlyOnce() throws Exception {
        Exchange e = newExchange("Before the end", "Zed", "Yan", "Xia");
        online(e, "Amy", "Bob", "Cat");
        JsonNode started = start(e);
        String current = started.get("current").asText();
        String next = started.get("queue").get(1).get("name").asText();
        setTurnEnd(e, 1500); // still in time

        claim(e, firstBookNotBy(e, current), current).andExpect(status().isOk());
        party.tick(e.code()); // the timer then runs: it must not advance the turn a second time
        party.tick(e.code());

        JsonNode after = party(e);
        assertThat(count(e, "DONE")).isEqualTo(1);
        assertThat(count(e, "CURRENT")).isEqualTo(1);
        assertThat(after.get("current").asText()).isEqualTo(next); // advanced once: to the next person in line
        assertThat(statuses(after)).contains(current + ":DONE");
        assertThat(after.get("turnSecondsLeft").asInt()).isGreaterThan(100); // and the next person has a fresh turn
    }

    @Test
    void theTimerMovesAnExpiredTurnToTheEndOfTheLine() throws Exception {
        Exchange e = newExchange("Expired", "Zed");
        online(e, "Amy", "Bob", "Cat");
        JsonNode started = start(e);
        List<String> order = new ArrayList<>();
        started.get("queue").forEach(q -> order.add(q.get("name").asText()));
        setTurnEnd(e, -1);

        party.tick(e.code());

        JsonNode after = party(e);
        List<String> expected = List.of(order.get(1), order.get(2), order.get(0));
        List<String> now = new ArrayList<>();
        after.get("queue").forEach(q -> now.add(q.get("name").asText()));
        assertThat(now).containsExactlyElementsOf(expected); // first one went to the end, nobody was removed
        assertThat(after.get("current").asText()).isEqualTo(order.get(1));
    }

    @Test
    void theTimerDoesNothingForAnExchangeWithoutAParty() throws Exception {
        Exchange e = newExchange("No party", "Zed");
        party.tick(e.code());
        party.tick("NOPE123");
        assertThat(party(e).get("active").asBoolean()).isFalse();
    }

    @Test
    void aClaimAndTheTimerAtTheSameMomentNeverBothHappen() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int claimsWon = 0;
        int timerWon = 0;
        for (int round = 0; round < 12; round++) {
            Exchange e = newExchange("Race " + round, "Zed", "Yan");
            online(e, "Amy", "Bob");
            String current = start(e).get("current").asText();
            long book = firstBookNotBy(e, current);
            setTurnEnd(e, 60); // the turn ends about now

            Future<Integer> byClaim = pool.submit((Callable<Integer>) () -> {
                Thread.sleep(40);
                return claim(e, book, current).andReturn().getResponse().getStatus();
            });
            Future<?> byTimer = pool.submit(() -> {
                try {
                    Thread.sleep(60);
                    party.tick(e.code());
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
            int result = byClaim.get(30, TimeUnit.SECONDS);
            byTimer.get(30, TimeUnit.SECONDS);
            party.tick(e.code()); // make sure the timer has had its say

            JsonNode after = party(e);
            assertThat(count(e, "CURRENT")).as("round %d: at most one current turn", round).isLessThanOrEqualTo(1);
            if (result == 200) {
                claimsWon++;
                assertThat(statuses(after)).as("round %d", round).contains(current + ":DONE");
                assertThat(count(e, "DONE")).isEqualTo(1); // advanced once
            } else {
                timerWon++;
                assertThat(result).isEqualTo(409);
                assertThat(statuses(after)).as("round %d", round).contains(current + ":WAITING");
                assertThat(count(e, "DONE")).isZero();
            }
        }
        pool.shutdown();
        assertThat(claimsWon + timerWon).isEqualTo(12); // every round had exactly one outcome
    }

    // ---- a round with nobody claiming ----

    @Test
    void aWholeRoundOfSkipsEndsThePartyAndAClaimStartsTheCountAgain() throws Exception {
        Exchange e = newExchange("Idle round", "Zed", "Yan", "Xia");
        online(e, "Amy", "Bob");
        start(e);

        mvc.perform(post("/api/exchanges/{c}/party/skip", e.code()).header("X-Host-Token", e.token())).andExpect(status().isOk());
        assertThat(party(e).get("active").asBoolean()).isTrue(); // one skipped turn: not a whole round yet
        String current = party(e).get("current").asText();
        claim(e, firstBookNotBy(e, current), current).andExpect(status().isOk()); // a claim resets the count
        assertThat(jdbc.queryForObject("select party_idle_turns from exchange where code = ?", Integer.class, e.code())).isZero();

        // one person is left in line, so a single skipped turn is now a whole round
        JsonNode ended = json.readTree(mvc.perform(post("/api/exchanges/{c}/party/skip", e.code())
                        .header("X-Host-Token", e.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ended.get("active").asBoolean()).isFalse();
        assertThat(ended.get("endedReason").asText()).contains("Nobody claimed a present for a whole round");
        assertThat(json.readTree(mvc.perform(get("/api/exchanges/{c}", e.code())).andReturn().getResponse().getContentAsString())
                .get("mode").asText()).isEqualTo("CLAIM");
    }
}
