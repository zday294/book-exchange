package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.live.ExchangeEventHub;
import com.bookexchange.support.PostgresTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Live updates over a real HTTP connection (Server-Sent Events), the way a browser uses them.
 * The heartbeat is fast and the connection cap small so those behaviours can be tested quickly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.events.heartbeat-millis=300", "app.events.max-subscribers=8"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class LiveUpdatesTest {

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ExchangeEventHub hub;

    private final List<SseClient> clients = new ArrayList<>();

    @AfterEach
    void disconnectEveryone() throws InterruptedException {
        clients.forEach(SseClient::close);
        clients.clear();
        // The server notices a closed browser at its next heartbeat; wait for that so the next test starts clean
        // (the connection cap is global).
        for (int i = 0; i < 100 && hub.totalSubscribers() > 0; i++) Thread.sleep(50);
        assertThat(hub.totalSubscribers()).as("connections left open by the test").isZero();
    }

    /** A minimal EventSource: reads the stream on a background thread and queues what arrives. */
    final class SseClient {
        record Message(String event, String data, boolean comment) {}

        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        final List<String> raw = new java.util.concurrent.CopyOnWriteArrayList<>();
        final int status;
        private final InputStream body;

        SseClient(String code) throws Exception {
            HttpResponse<InputStream> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/exchanges/" + code + "/events"))
                            .header("Accept", "text/event-stream").build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            body = response.body();
            if (status == 200) {
                assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
                Thread reader = new Thread(this::read, "sse-test-reader");
                reader.setDaemon(true);
                reader.start();
            }
            clients.add(this);
        }

        private void read() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String event = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    raw.add(line);
                    if (line.startsWith(":")) {
                        messages.add(new Message(null, line.substring(1).strip(), true));
                    } else if (line.startsWith("event:")) {
                        event = line.substring(6).strip();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).strip());
                    } else if (line.isEmpty() && event != null) {
                        messages.add(new Message(event, data.toString(), false));
                        event = null;
                        data.setLength(0);
                    }
                }
            } catch (IOException closed) {
                // the test closed the connection
            }
        }

        /** The next real event (comments are skipped), within a few seconds. */
        Message next() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                Message m = messages.poll(100, TimeUnit.MILLISECONDS);
                if (m != null && !m.comment()) return m;
            }
            throw new AssertionError("no event arrived within 5 seconds; raw lines so far: " + raw);
        }

        Message nextNamed(String name) throws InterruptedException {
            Message m = next();
            assertThat(m.event()).isEqualTo(name);
            return m;
        }

        boolean sawComment(String text, long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (System.nanoTime() < deadline) {
                Message m = messages.poll(50, TimeUnit.MILLISECONDS);
                if (m != null && m.comment() && m.data().equals(text)) return true;
            }
            return false;
        }

        /** Everything that arrives in the next moment (to prove that something does NOT arrive). */
        List<Message> drain(long millis) throws InterruptedException {
            Thread.sleep(millis);
            List<Message> out = new ArrayList<>();
            messages.drainTo(out);
            out.removeIf(Message::comment);
            return out;
        }

        void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    // ---- helpers to make things happen ----

    private record Created(String code, String token) {}

    private Created newExchange(String name) throws Exception {
        JsonNode n = json.readTree(mvc.perform(post("/api/exchanges").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "organizerName", "Host"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Created(n.get("code").asText(), n.get("hostToken").asText());
    }

    private long submit(String code, String who) throws Exception {
        return json.readTree(mvc.perform(post("/api/exchanges/{c}/books", code).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submitterName\":\"" + who + "\",\"bookTitle\":\"Secret Title\","
                                + "\"amazonUrl\":\"https://www.amazon.com/dp/0547928221\",\"wrappingPaper\":\"holly\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private void switchTo(Created c, String mode) throws Exception {
        mvc.perform(put("/api/exchanges/{c}/mode", c.code()).header("X-Host-Token", c.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"" + mode + "\"}")).andExpect(status().isOk());
    }

    private void claim(String code, long id, String who) throws Exception {
        mvc.perform(post("/api/exchanges/{c}/books/{id}/claim", code, id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantName\":\"" + who + "\"}")).andExpect(status().isOk());
    }

    // ---- tests ----

    @Test
    void theConnectionOpensAndAnnouncesItselfAtOnce() throws Exception {
        Created c = newExchange("Ready");
        SseClient client = new SseClient(c.code());
        assertThat(client.status).isEqualTo(200);
        assertThat(client.nextNamed("ready").data()).isEqualTo("{}");
    }

    @Test
    void aNewBookAppearsAsAWrappedPresentAndNothingMore() throws Exception {
        Created c = newExchange("Added");
        SseClient client = new SseClient(c.code());
        client.nextNamed("ready");

        long id = submit(c.code(), "Zach");

        SseClient.Message m = client.nextNamed("book-added");
        JsonNode data = json.readTree(m.data());
        assertThat(data.get("id").asLong()).isEqualTo(id);
        assertThat(data.get("submitterName").asText()).isEqualTo("Zach");
        assertThat(data.get("wrappingPaper").asText()).isEqualTo("holly");
        List<String> fields = new ArrayList<>();
        data.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "submitterName", "wrappingPaper");
        assertThat(m.data()).doesNotContain("Secret Title").doesNotContain("amazon");
    }

    @Test
    void aClaimShowsWhatIsPublicAndNeverTheBookLink() throws Exception {
        Created c = newExchange("Claimed");
        long id = submit(c.code(), "Zach");
        switchTo(c, "CLAIM");
        SseClient client = new SseClient(c.code());
        client.nextNamed("ready");

        claim(c.code(), id, "Alex");

        SseClient.Message m = client.nextNamed("book-claimed");
        JsonNode data = json.readTree(m.data());
        assertThat(data.get("id").asLong()).isEqualTo(id);
        assertThat(data.get("claimedByName").asText()).isEqualTo("Alex");
        assertThat(data.get("bookTitle").asText()).isEqualTo("Secret Title");
        assertThat(data.get("claimedAt").asText()).isNotBlank();
        assertThat(data.has("coverImageUrl")).isFalse(); // no cover yet: the field is left out, not null
        assertThat(m.data()).doesNotContain("amazon").doesNotContain("http");
    }

    @Test
    void switchingTheModeIsAnnounced() throws Exception {
        Created c = newExchange("Mode");
        SseClient client = new SseClient(c.code());
        client.nextNamed("ready");

        switchTo(c, "CLAIM");
        assertThat(json.readTree(client.nextNamed("mode-changed").data()).get("mode").asText()).isEqualTo("CLAIM");
        switchTo(c, "SUBMIT");
        assertThat(json.readTree(client.nextNamed("mode-changed").data()).get("mode").asText()).isEqualTo("SUBMIT");
    }

    @Test
    void everyoneWatchingGetsTheEventInOrder() throws Exception {
        Created c = newExchange("Many watchers");
        List<SseClient> watchers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            SseClient w = new SseClient(c.code());
            w.nextNamed("ready");
            watchers.add(w);
        }

        long first = submit(c.code(), "A");
        long second = submit(c.code(), "B");

        for (SseClient w : watchers) {
            assertThat(json.readTree(w.nextNamed("book-added").data()).get("id").asLong()).isEqualTo(first);
            assertThat(json.readTree(w.nextNamed("book-added").data()).get("id").asLong()).isEqualTo(second);
        }
    }

    @Test
    void eventsOfOneExchangeNeverReachWatchersOfAnother() throws Exception {
        Created a = newExchange("Exchange A");
        Created b = newExchange("Exchange B");
        SseClient watchingA = new SseClient(a.code());
        SseClient watchingB = new SseClient(b.code());
        watchingA.nextNamed("ready");
        watchingB.nextNamed("ready");

        long inB = submit(b.code(), "Betty");
        switchTo(b, "CLAIM");
        long inA = submit(a.code(), "Al");

        assertThat(json.readTree(watchingA.nextNamed("book-added").data()).get("id").asLong()).isEqualTo(inA);
        assertThat(watchingA.drain(300)).isEmpty(); // nothing from B, not even the mode change
        assertThat(json.readTree(watchingB.nextNamed("book-added").data()).get("id").asLong()).isEqualTo(inB);
        assertThat(watchingB.nextNamed("mode-changed")).isNotNull();
    }

    @Test
    void rejectedRequestsAnnounceNothing() throws Exception {
        Created c = newExchange("Rejected");
        long id = submit(c.code(), "Zach");
        SseClient client = new SseClient(c.code());
        client.nextNamed("ready");

        // a duplicate submission, a claim before claiming opens, and a submission after it closes: all 409
        mvc.perform(post("/api/exchanges/{c}/books", c.code()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"submitterName\":\"Zach\",\"bookTitle\":\"Again\","
                        + "\"amazonUrl\":\"https://www.amazon.com/dp/1\",\"wrappingPaper\":\"holly\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/exchanges/{c}/books/{id}/claim", c.code(), id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantName\":\"Alex\"}")).andExpect(status().isConflict());
        switchTo(c, "CLAIM");
        mvc.perform(post("/api/exchanges/{c}/books", c.code()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"submitterName\":\"Late\",\"bookTitle\":\"X\","
                        + "\"amazonUrl\":\"https://www.amazon.com/dp/1\",\"wrappingPaper\":\"holly\"}")).andExpect(status().isConflict());

        // the only thing announced is the mode change that really happened
        assertThat(client.nextNamed("mode-changed")).isNotNull();
        assertThat(client.drain(300)).isEmpty();
    }

    @Test
    void anUnknownExchangeIs404WithTheUsualJsonError() throws Exception {
        assertThat(new SseClient("NOPE123").status).isEqualTo(404); // even though the caller asked for an event stream
        mvc.perform(get("/api/exchanges/NOPE123/events")).andExpect(status().isNotFound());
    }

    @Test
    void aHeartbeatKeepsTheConnectionAlive() throws Exception {
        Created c = newExchange("Heartbeat");
        SseClient client = new SseClient(c.code());
        client.nextNamed("ready");
        assertThat(client.sawComment("keep-alive", 3000)).isTrue();
        assertThat(client.sawComment("keep-alive", 3000)).isTrue(); // and keeps coming
    }

    @Test
    void aConnectionThatWentAwayIsCleanedUp() throws Exception {
        Created c = newExchange("Cleanup");
        SseClient stays = new SseClient(c.code());
        SseClient leaves = new SseClient(c.code());
        stays.nextNamed("ready");
        leaves.nextNamed("ready");
        assertThat(hub.subscriberCount(c.code())).isEqualTo(2);

        leaves.close();

        for (int i = 0; i < 60 && hub.subscriberCount(c.code()) != 1; i++) Thread.sleep(100); // the heartbeat notices
        assertThat(hub.subscriberCount(c.code())).isEqualTo(1);
        long id = submit(c.code(), "Still works"); // and the one that stayed is unaffected
        assertThat(json.readTree(stays.nextNamed("book-added").data()).get("id").asLong()).isEqualTo(id);
    }

    @Test
    void theNumberOfOpenConnectionsIsCapped() throws Exception {
        Created c = newExchange("Capped");
        List<SseClient> open = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            SseClient client = new SseClient(c.code());
            assertThat(client.status).isEqualTo(200);
            open.add(client);
        }

        assertThat(new SseClient(c.code()).status).isEqualTo(503);

        open.get(0).close(); // room again once someone leaves
        for (int i = 0; i < 60 && hub.subscriberCount(c.code()) >= 8; i++) Thread.sleep(100);
        assertThat(new SseClient(c.code()).status).isEqualTo(200);
    }
}
