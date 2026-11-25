package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.support.PostgresTestConfig;
import com.bookexchange.support.SseTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;

/** Going offline, with turns long enough (30 s) that only the offline rule, not the clock, can end a turn. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"app.link-check.enabled=false", "app.party.turn-seconds=30", "app.party.offline-grace-seconds=1", "app.party.tick-millis=150",
        "app.events.heartbeat-millis=150"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class PartyOfflineTest {

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private final List<SseTestClient> clients = new ArrayList<>();

    @AfterEach
    void goOffline() {
        clients.forEach(SseTestClient::close);
        clients.clear();
    }

    private SseTestClient online(String code, String name) throws Exception {
        SseTestClient c = new SseTestClient(port, code, name).readyOrFail();
        clients.add(c);
        return c;
    }

    private JsonNode party(String code) throws Exception {
        return json.readTree(mvc.perform(get("/api/exchanges/{c}/party", code)).andReturn().getResponse().getContentAsString());
    }

    private String currentOf(String code) {
        try {
            JsonNode c = party(code).get("current");
            return c == null || c.isNull() ? null : c.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private void until(BooleanSupplier condition, int seconds, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    @Test
    void goingOfflineLosesTheTurnAfterTheGracePeriodButARefreshDoesNot() throws Exception {
        JsonNode created = json.readTree(mvc.perform(post("/api/exchanges").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Offline", "organizerName", "Host"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String code = created.get("code").asText();
        String token = created.get("hostToken").asText();
        for (String who : List.of("Zed", "Yan")) {
            mvc.perform(post("/api/exchanges/{c}/books", code).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"submitterName\":\"" + who + "\",\"bookTitle\":\"T\",\"bookUrl\":\"https://www.amazon.com/dp/1\","
                            + "\"wrappingPaper\":\"holly\"}")).andExpect(status().isCreated());
        }
        SseTestClient amy = online(code, "Amy");
        online(code, "Bob");

        // Start until it is Amy's turn first (the order is random).
        for (int i = 0; i < 12; i++) {
            mvc.perform(post("/api/exchanges/{c}/party/start", code).header("X-Host-Token", token)).andExpect(status().isOk());
            if ("Amy".equals(currentOf(code))) break;
            mvc.perform(post("/api/exchanges/{c}/party/end", code).header("X-Host-Token", token)).andExpect(status().isOk());
        }
        assertThat(currentOf(code)).isEqualTo("Amy");

        // A refresh: the page closes and reopens straight away, well within the grace period.
        amy.close();
        Thread.sleep(300);
        SseTestClient back = online(code, "Amy");
        Thread.sleep(1500); // longer than the grace period
        assertThat(currentOf(code)).isEqualTo("Amy"); // still her turn

        // Really leaving: after the grace period the turn passes to the next person.
        back.close();
        until(() -> !"Amy".equals(currentOf(code)), 8, "Amy's turn to pass after she went offline");
        JsonNode after = party(code);
        assertThat(after.get("current").asText()).isEqualTo("Bob");
        JsonNode amyEntry = null;
        for (JsonNode q : after.get("queue")) if (q.get("name").asText().equals("Amy")) amyEntry = q;
        assertThat(amyEntry).isNotNull(); // moved to the end, not removed
        assertThat(amyEntry.get("status").asText()).isEqualTo("WAITING");
        assertThat(amyEntry.get("online").asBoolean()).isFalse();
        assertThat(after.get("queue").get(after.get("queue").size() - 1).get("name").asText()).isEqualTo("Amy");
    }

    @Test
    void aPartyKeepsMovingWhenSomeoneInTheQueueIsOffline() throws Exception {
        JsonNode created = json.readTree(mvc.perform(post("/api/exchanges").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Offline later", "organizerName", "Host"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String code = created.get("code").asText();
        String token = created.get("hostToken").asText();
        mvc.perform(post("/api/exchanges/{c}/books", code).contentType(MediaType.APPLICATION_JSON)
                .content("{\"submitterName\":\"Zed\",\"bookTitle\":\"T\",\"bookUrl\":\"https://www.amazon.com/dp/1\","
                        + "\"wrappingPaper\":\"holly\"}")).andExpect(status().isCreated());
        online(code, "Amy");
        SseTestClient bob = online(code, "Bob");
        online(code, "Cat");
        mvc.perform(post("/api/exchanges/{c}/party/start", code).header("X-Host-Token", token)).andExpect(status().isOk());

        bob.close(); // gone, whenever his turn comes
        // Whenever the turn lands on Bob it passes again once his grace period is over, so the turn keeps
        // landing on someone, and after a while it is only ever on the people who are online.
        until(() -> {
            String c = currentOf(code);
            return c != null && !"Bob".equals(c);
        }, 8, "the turn to rest with someone online");
        for (int i = 0; i < 10; i++) {
            Thread.sleep(250);
            assertThat(currentOf(code)).as("the party never stalls without a current turn").isNotNull();
        }
    }
}
