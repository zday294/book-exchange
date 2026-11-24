package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.support.PostgresTestConfig;
import com.bookexchange.support.SseTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
 * The party, with real timing: short turns, a short offline grace period and a fast timer, with people "online"
 * through real live connections (that is how the server knows who is there).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.party.turn-seconds=2", "app.party.offline-grace-seconds=1", "app.party.tick-millis=150",
        "app.events.heartbeat-millis=150", "app.events.max-subscribers=200"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class PartyTest {

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private final List<SseTestClient> clients = new ArrayList<>();

    @AfterEach
    void goOffline() {
        clients.forEach(SseTestClient::close);
        clients.clear();
    }

    private record Exchange(String code, String token) {}

    private Exchange newExchange(String name) throws Exception {
        JsonNode n = json.readTree(mvc.perform(post("/api/exchanges").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "organizerName", "Host"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Exchange(n.get("code").asText(), n.get("hostToken").asText());
    }

    private long book(Exchange e, String submitter) throws Exception {
        return json.readTree(mvc.perform(post("/api/exchanges/{c}/books", e.code()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submitterName\":\"" + submitter + "\",\"bookTitle\":\"Secret " + submitter + "\","
                                + "\"amazonUrl\":\"https://www.amazon.com/dp/0547928221\",\"wrappingPaper\":\"holly\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    /** Puts a person online (a live connection with their name). */
    private SseTestClient online(Exchange e, String name) throws Exception {
        SseTestClient client = new SseTestClient(port, e.code(), name).readyOrFail();
        clients.add(client);
        return client;
    }

    private ResultActions host(Exchange e, String action) throws Exception {
        return mvc.perform(post("/api/exchanges/{c}/party/" + action, e.code()).header("X-Host-Token", e.token()));
    }

    private JsonNode party(Exchange e) throws Exception {
        return json.readTree(mvc.perform(get("/api/exchanges/{c}/party", e.code())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode startParty(Exchange e) throws Exception {
        return json.readTree(host(e, "start").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions claim(Exchange e, long bookId, String who) throws Exception {
        return mvc.perform(post("/api/exchanges/{c}/books/{id}/claim", e.code(), bookId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"participantName\":\"" + who + "\"}"));
    }

    private List<String> names(JsonNode party) {
        List<String> out = new ArrayList<>();
        party.get("queue").forEach(q -> out.add(q.get("name").asText()));
        return out;
    }

    private String statusOf(JsonNode party, String name) {
        for (JsonNode q : party.get("queue")) if (q.get("name").asText().equals(name)) return q.get("status").asText();
        return null;
    }

    private void until(BooleanSupplier condition, int seconds, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    private String mode(Exchange e) throws Exception {
        return json.readTree(mvc.perform(get("/api/exchanges/{c}", e.code())).andReturn().getResponse().getContentAsString())
                .get("mode").asText();
    }

    // ---- starting ----

    @Test
    void onlyTheHostCanStartAndSomeoneMustBeOnlineAndThereMustBePresents() throws Exception {
        Exchange e = newExchange("Starting");
        book(e, "Zed");
        online(e, "Amy");

        mvc.perform(post("/api/exchanges/{c}/party/start", e.code())).andExpect(status().isForbidden());
        mvc.perform(post("/api/exchanges/{c}/party/start", e.code()).header("X-Host-Token", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/exchanges/NOPE123/party/start").header("X-Host-Token", "x")).andExpect(status().isNotFound());

        Exchange empty = newExchange("No presents");
        online(empty, "Amy");
        host(empty, "start").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("There are no presents left to claim"));

        Exchange lonely = newExchange("Nobody here");
        book(lonely, "Zed");
        host(lonely, "start").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Nobody is online")));
        assertThat(mode(lonely)).isEqualTo("SUBMIT");
    }

    @Test
    void startingQueuesEveryoneOnlineWhoHasNotClaimedAndGivesTheFirstPersonTheTurn() throws Exception {
        Exchange e = newExchange("Queue");
        long first = book(e, "Zed");
        book(e, "Yan");
        book(e, "Xia");
        mvc.perform(put("/api/exchanges/{c}/mode", e.code()).header("X-Host-Token", e.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"CLAIM\"}")).andExpect(status().isOk());
        online(e, "Amy");
        online(e, "Bob");
        online(e, "Cat");
        SseTestClient watcher = online(e, "Dan");
        claim(e, first, "Dan").andExpect(status().isOk()); // Dan already has a book, so he is not queued

        JsonNode state = startParty(e);

        assertThat(state.get("active").asBoolean()).isTrue();
        assertThat(names(state)).containsExactlyInAnyOrder("Amy", "Bob", "Cat");
        assertThat(state.get("current").asText()).isIn("Amy", "Bob", "Cat");
        assertThat(statusOf(state, state.get("current").asText())).isEqualTo("CURRENT");
        assertThat(state.get("queue").get(0).get("status").asText()).isEqualTo("CURRENT"); // nobody is done yet
        for (JsonNode q : state.get("queue")) assertThat(q.get("online").asBoolean()).isTrue();
        assertThat(state.get("turnSecondsLeft").asInt()).isBetween(1, 2);
        assertThat(mode(e)).isEqualTo("PARTY");

        assertThat(json.readTree(watcher.waitFor("mode-changed", 5).data()).get("mode").asText()).isEqualTo("PARTY");
        JsonNode event = json.readTree(watcher.waitFor("party-changed", 5).data());
        assertThat(names(event)).containsExactlyInAnyOrder("Amy", "Bob", "Cat");
    }

    @Test
    void theOrderIsRandom() throws Exception {
        Exchange e = newExchange("Random");
        book(e, "Zed");
        for (String n : List.of("Amy", "Bob", "Cat", "Dan")) online(e, n);

        Set<List<String>> orders = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            orders.add(names(startParty(e)));
            host(e, "end").andExpect(status().isOk());
        }
        assertThat(orders.size()).isGreaterThan(1); // 8 starts of 4 people: not always the same order
    }

    @Test
    void startingFromSubmitModeSwitchesToPartyAndBlocksSubmissions() throws Exception {
        Exchange e = newExchange("From submit");
        book(e, "Zed");
        online(e, "Amy");
        assertThat(mode(e)).isEqualTo("SUBMIT");

        startParty(e);

        assertThat(mode(e)).isEqualTo("PARTY");
        mvc.perform(post("/api/exchanges/{c}/books", e.code()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"submitterName\":\"Late\",\"bookTitle\":\"X\",\"amazonUrl\":\"https://www.amazon.com/dp/1\","
                        + "\"wrappingPaper\":\"holly\"}")).andExpect(status().isConflict());
        host(e, "start").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("A party is already running"));
    }

    @Test
    void theModeCannotBeSwitchedToPartyOrAwayFromItBySwitching() throws Exception {
        Exchange e = newExchange("Switching");
        book(e, "Zed");
        online(e, "Amy");
        mvc.perform(put("/api/exchanges/{c}/mode", e.code()).header("X-Host-Token", e.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"PARTY\"}")).andExpect(status().isBadRequest());

        startParty(e);
        mvc.perform(put("/api/exchanges/{c}/mode", e.code()).header("X-Host-Token", e.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"SUBMIT\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("A party is running. End the party first."));
    }

    // ---- claiming in a party ----

    @Test
    void onlyThePersonWhoseTurnItIsCanClaimAndThenTheTurnMoves() throws Exception {
        Exchange e = newExchange("Turns");
        long a = book(e, "Zed");
        long b = book(e, "Yan");
        book(e, "Xia");
        online(e, "Amy");
        online(e, "Bob");
        SseTestClient watcher = online(e, "Cat");
        JsonNode state = startParty(e);
        String current = state.get("current").asText();
        String other = names(state).stream().filter(n -> !n.equals(current)).findFirst().orElseThrow();
        watcher.drain(100);

        claim(e, a, other).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("It's not your turn; " + current + " is up"));
        assertThat(jdbc.queryForObject("select claimed_by_name from book_submission where id = ?", String.class, a)).isNull();
        assertThat(party(e).get("current").asText()).isEqualTo(current); // nothing changed

        claim(e, a, current.toUpperCase()).andExpect(status().isOk()) // names are matched ignoring case
                .andExpect(jsonPath("$.claimedByName").value(current.toUpperCase()));

        JsonNode after = party(e);
        assertThat(statusOf(after, current)).isEqualTo("DONE");
        assertThat(after.get("current").asText()).isNotEqualTo(current);
        assertThat(after.get("queue").get(0).get("name").asText()).isEqualTo(current); // done people come first
        JsonNode event = json.readTree(watcher.waitFor("party-changed", 5).data());
        assertThat(event.get("current").asText()).isEqualTo(after.get("current").asText());
        claim(e, b, current).andExpect(status().isConflict()); // and they can't claim again
    }

    @Test
    void aRejectedClaimDoesNotUseUpTheTurn() throws Exception {
        Exchange e = newExchange("Own book");
        online(e, "Amy");
        long own = book(e, "Amy");
        book(e, "Zed");
        String current = startParty(e).get("current").asText();
        assertThat(current).isEqualTo("Amy");

        claim(e, own, "Amy").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("yourself")));

        JsonNode state = party(e);
        assertThat(state.get("current").asText()).isEqualTo("Amy"); // still her turn
        assertThat(statusOf(state, "Amy")).isEqualTo("CURRENT");
    }

    @Test
    void whenTheCurrentPersonAndSomeoneLaterInLineClaimAtTheSameMomentOnlyTheCurrentPersonWins() throws Exception {
        Exchange e = newExchange("Simultaneous");
        long one = book(e, "Zed");
        long two = book(e, "Yan");
        for (String n : List.of("Amy", "Bob", "Cat")) online(e, n);
        JsonNode state = startParty(e);
        String current = state.get("current").asText();
        String last = state.get("queue").get(2).get("name").asText(); // not the next person: their turn is far off

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> byCurrent = pool.submit((Callable<Integer>) () -> claim(e, one, current).andReturn().getResponse().getStatus());
        Future<Integer> byLast = pool.submit((Callable<Integer>) () -> claim(e, two, last).andReturn().getResponse().getStatus());
        int currentResult = byCurrent.get(30, TimeUnit.SECONDS);
        int lastResult = byLast.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(currentResult).isEqualTo(200);
        assertThat(lastResult).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from book_submission where claimed_by_name is not null "
                + "and exchange_id = (select id from exchange where code = ?)", Integer.class, e.code())).isEqualTo(1);
    }

    @Test
    void claimsThatPileUpAreHandledOneAtATimeAndAlwaysByWhoseTurnItIs() throws Exception {
        Exchange e = newExchange("Pile up");
        List<Long> ids = new ArrayList<>();
        for (String s : List.of("Zed", "Yan", "Xia", "Wu", "Vic")) ids.add(book(e, s));
        for (String n : List.of("Amy", "Bob", "Cat", "Dan")) online(e, n);
        JsonNode started = startParty(e);
        List<String> order = names(started);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 12; i++) { // everyone tries repeatedly, all at once
            String who = order.get(i % order.size());
            long id = ids.get(i % ids.size());
            results.add(pool.submit((Callable<Integer>) () -> claim(e, id, who).andReturn().getResponse().getStatus()));
        }
        for (Future<Integer> f : results) assertThat(f.get(30, TimeUnit.SECONDS)).isIn(200, 409);
        pool.shutdown();

        // Whatever the timing: nobody claimed twice, the first person in line got the first claim, and the
        // queue shows exactly the people who really claimed as done, in turn order.
        List<String> claimers = jdbc.queryForList("select claimed_by_name from book_submission where claimed_by_name is not null "
                + "and exchange_id = (select id from exchange where code = ?) order by claimed_at, id", String.class, e.code());
        assertThat(claimers).doesNotHaveDuplicates().isNotEmpty();
        assertThat(claimers.get(0)).isEqualTo(order.get(0));
        JsonNode after = party(e);
        List<String> done = new ArrayList<>();
        after.get("queue").forEach(q -> { if (q.get("status").asText().equals("DONE")) done.add(q.get("name").asText()); });
        assertThat(done).containsExactlyElementsOf(claimers);
        assertThat(order.subList(0, claimers.size())).containsExactlyElementsOf(claimers); // in the order of the queue
    }

    @Test
    void whenTheLastPresentIsClaimedThePartyEndsByItself() throws Exception {
        Exchange e = newExchange("Last present");
        long one = book(e, "Zed");
        long two = book(e, "Yan");
        online(e, "Amy");
        SseTestClient watcher = online(e, "Bob");
        JsonNode state = startParty(e);
        String first = state.get("current").asText();
        String second = names(state).stream().filter(n -> !n.equals(first)).findFirst().orElseThrow();

        claim(e, one, first).andExpect(status().isOk());
        assertThat(mode(e)).isEqualTo("PARTY");
        claim(e, two, second).andExpect(status().isOk());

        assertThat(mode(e)).isEqualTo("CLAIM");
        JsonNode ended = party(e);
        assertThat(ended.get("active").asBoolean()).isFalse();
        JsonNode last = null;
        for (int i = 0; i < 20 && (last == null || last.get("active").asBoolean()); i++) {
            last = json.readTree(watcher.waitFor("party-changed", 5).data());
        }
        assertThat(last.get("endedReason").asText()).contains("All the presents have been claimed");
    }

    // ---- time ----

    @Test
    void aTurnThatRunsOutMovesThatPersonToTheEndAndStartsTheNextTurn() throws Exception {
        Exchange e = newExchange("Timeout");
        book(e, "Zed");
        book(e, "Yan");
        book(e, "Xia");
        for (String n : List.of("Amy", "Bob", "Cat")) online(e, n);
        JsonNode state = startParty(e);
        String first = state.get("current").asText();
        String next = state.get("queue").get(1).get("name").asText();

        until(() -> {
            try {
                return !party(e).get("current").asText().equals(first);
            } catch (Exception ex) {
                return false;
            }
        }, 6, "the first turn to run out");

        JsonNode after = party(e);
        assertThat(after.get("current").asText()).isEqualTo(next);
        assertThat(names(after)).contains(first).hasSize(3); // still in the queue, not removed
        assertThat(statusOf(after, first)).isEqualTo("WAITING");
        assertThat(names(after).get(names(after).size() - 1)).isEqualTo(first); // at the end of the line
        assertThat(after.get("turnSecondsLeft").asInt()).isBetween(1, 2); // the next person has a fresh turn
    }

    @Test
    void aWholeRoundWithoutAClaimEndsTheParty() throws Exception {
        Exchange e = newExchange("Nobody plays");
        book(e, "Zed");
        online(e, "Amy");
        online(e, "Bob");
        startParty(e);

        until(() -> {
            try {
                return "CLAIM".equals(mode(e));
            } catch (Exception ex) {
                return false;
            }
        }, 12, "the party to end after a round with no claim");
        assertThat(party(e).get("active").asBoolean()).isFalse();
    }

    // ---- late arrivals ----

    @Test
    void peopleWhoArriveLateJoinTheEndAndPeopleWhoAlreadyClaimedDoNot() throws Exception {
        Exchange e = newExchange("Late");
        long gift = book(e, "Zed");
        book(e, "Yan");
        book(e, "Xia");
        online(e, "Amy");
        online(e, "Bob");
        startParty(e);

        online(e, "Cat");
        until(() -> {
            try {
                return names(party(e)).contains("Cat");
            } catch (Exception ex) {
                return false;
            }
        }, 5, "Cat to join the queue");
        List<String> queue = names(party(e));
        assertThat(queue.get(queue.size() - 1)).isEqualTo("Cat");
        assertThat(statusOf(party(e), "Cat")).isEqualTo("WAITING");

        online(e, "Cat"); // a second tab of the same person: not queued twice
        Thread.sleep(400);
        assertThat(names(party(e)).stream().filter("Cat"::equals).count()).isEqualTo(1);

        String current = party(e).get("current").asText();
        claim(e, gift, current).andExpect(status().isOk());
        online(e, current); // someone who already claimed comes back: not queued again
        Thread.sleep(400);
        assertThat(names(party(e)).stream().filter(current::equals).count()).isEqualTo(1);
        assertThat(statusOf(party(e), current)).isEqualTo("DONE");
    }

    @Test
    void aPartyWithNobodyLeftWaitsForLateArrivalsWhoGetTheTurnAtOnce() throws Exception {
        Exchange e = newExchange("Waiting for someone");
        long gift = book(e, "Zed");
        book(e, "Yan"); // two presents, but only one person is playing
        online(e, "Amy");
        startParty(e);

        claim(e, gift, "Amy").andExpect(status().isOk());
        JsonNode waiting = party(e);
        assertThat(waiting.get("active").asBoolean()).isTrue(); // a present is left, so the party stays open
        assertThat(waiting.has("current") && !waiting.get("current").isNull()).isFalse();

        online(e, "Bob"); // a late arrival
        until(() -> {
            try {
                JsonNode p = party(e);
                return p.has("current") && !p.get("current").isNull() && p.get("current").asText().equals("Bob");
            } catch (Exception ex) {
                return false;
            }
        }, 5, "Bob to get the turn");
    }

    // ---- the host ----

    @Test
    void theHostCanSkipSomeoneAndTheyGoToTheEnd() throws Exception {
        Exchange e = newExchange("Skipping");
        book(e, "Zed");
        for (String n : List.of("Amy", "Bob", "Cat")) online(e, n);
        JsonNode state = startParty(e);
        String first = state.get("current").asText();
        String second = state.get("queue").get(1).get("name").asText();

        mvc.perform(post("/api/exchanges/{c}/party/skip", e.code())).andExpect(status().isForbidden());
        JsonNode after = json.readTree(host(e, "skip").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(after.get("current").asText()).isEqualTo(second);
        List<String> order = names(after);
        assertThat(order.get(order.size() - 1)).isEqualTo(first);
        assertThat(order).hasSize(3);
    }

    @Test
    void skippingOrEndingWithoutAPartyIs409() throws Exception {
        Exchange e = newExchange("No party");
        book(e, "Zed");
        host(e, "skip").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("There is no party running"));
        host(e, "end").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("There is no party running"));
    }

    @Test
    void endingThePartyGoesBackToClaimModeWithClaimsIntact() throws Exception {
        Exchange e = newExchange("Ending");
        long first = book(e, "Zed");
        long second = book(e, "Yan");
        book(e, "Xia");
        online(e, "Amy");
        SseTestClient watcher = online(e, "Bob");
        String current = startParty(e).get("current").asText();
        claim(e, first, current).andExpect(status().isOk());

        mvc.perform(post("/api/exchanges/{c}/party/end", e.code())).andExpect(status().isForbidden());
        JsonNode ended = json.readTree(host(e, "end").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(ended.get("active").asBoolean()).isFalse();
        assertThat(ended.get("endedReason").asText()).isEqualTo("The host ended the party.");
        assertThat(mode(e)).isEqualTo("CLAIM");
        assertThat(jdbc.queryForObject("select claimed_by_name from book_submission where id = ?", String.class, first))
                .isEqualToIgnoringCase(current); // the claim survived
        assertThat(jdbc.queryForObject("select count(*) from party_turn where exchange_id = "
                + "(select id from exchange where code = ?)", Integer.class, e.code())).isZero();

        // free-for-all again: anyone (who hasn't claimed) can claim, whenever
        String other = current.equals("Amy") ? "Bob" : "Amy";
        claim(e, second, other).andExpect(status().isOk());

        JsonNode last = null;
        for (int i = 0; i < 20 && (last == null || last.get("active").asBoolean()); i++) {
            last = json.readTree(watcher.waitFor("party-changed", 5).data());
        }
        assertThat(last.get("endedReason").asText()).isEqualTo("The host ended the party.");
    }

    // ---- what is public ----

    @Test
    void thePartyAndItsEventsNeverShowAnythingAboutTheBooks() throws Exception {
        Exchange e = newExchange("Names only");
        long gift = book(e, "Zed");
        book(e, "Yan");
        online(e, "Amy");
        SseTestClient watcher = online(e, "Bob");
        String current = startParty(e).get("current").asText();
        claim(e, gift, current).andExpect(status().isOk());

        for (SseTestClient.Message m : watcher.drain(500)) {
            if (m.event().equals("party-changed")) {
                assertThat(m.data()).doesNotContain("Secret").doesNotContain("amazon").doesNotContain("http");
                JsonNode data = json.readTree(m.data());
                List<String> fields = new ArrayList<>();
                data.fieldNames().forEachRemaining(fields::add);
                assertThat(fields).isSubsetOf("active", "endedReason", "current", "turnSecondsLeft", "queue", "eligible");
            }
        }
        assertThat(party(e).toString()).doesNotContain("Secret").doesNotContain("amazon");
    }

    @Test
    void theStateWhenNoPartyIsRunningListsWhoCouldBeQueued() throws Exception {
        Exchange e = newExchange("Inactive");
        long gift = book(e, "Zed");
        online(e, "Amy");
        online(e, "Bob");
        mvc.perform(put("/api/exchanges/{c}/mode", e.code()).header("X-Host-Token", e.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"CLAIM\"}")).andExpect(status().isOk());
        claim(e, gift, "Bob").andExpect(status().isOk());

        JsonNode state = party(e);
        assertThat(state.get("active").asBoolean()).isFalse();
        assertThat(state.get("queue")).isEmpty();
        List<String> eligible = new ArrayList<>();
        state.get("eligible").forEach(n -> eligible.add(n.asText()));
        assertThat(eligible).containsExactly("Amy"); // Bob already claimed

        mvc.perform(get("/api/exchanges/NOPE123/party")).andExpect(status().isNotFound());
    }
}
