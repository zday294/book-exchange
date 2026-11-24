package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.domain.Exchange;
import com.bookexchange.exchange.HostTokens;
import com.bookexchange.repository.ExchangeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class ExchangeHostTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ExchangeRepository exchanges;
    @Autowired HostTokens tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private record Created(String code, String token) {}

    private Created create(String name) throws Exception {
        String body = mvc.perform(post("/api/exchanges").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "organizerName", "Zach"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(body);
        return new Created(n.get("code").asText(), n.get("hostToken").asText());
    }

    private ResultActions getAs(String code, String token) throws Exception {
        var request = get("/api/exchanges/{c}", code);
        if (token != null) request.header("X-Host-Token", token);
        return mvc.perform(request);
    }

    private ResultActions patchAs(String code, String token, Object body) throws Exception {
        var request = patch("/api/exchanges/{c}", code).contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : json.writeValueAsString(body));
        if (token != null) request.header("X-Host-Token", token);
        return mvc.perform(request);
    }

    // ---- the token ----

    @Test
    void creatingAnExchangeReturnsTheHostTokenOnceAndMakesTheCreatorTheHost() throws Exception {
        String body = mvc.perform(post("/api/exchanges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hosted\",\"organizerName\":\"Zach\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isHost").value(true))
                .andExpect(jsonPath("$.hostToken").isString())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).get("hostToken").asText();
        assertThat(token).matches("[A-Za-z0-9_-]{43}"); // 256 random bits, URL-safe
    }

    @Test
    void tokensAreDifferentForEveryExchange() throws Exception {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 12; i++) seen.add(create("Distinct " + i).token());
        assertThat(seen).hasSize(12);
    }

    @Test
    void onlyAHashIsStoredNeverTheToken() throws Exception {
        Created c = create("Hashed");
        String stored = jdbc.queryForObject("select host_token_hash from exchange where code = ?", String.class, c.code());
        assertThat(stored).matches("[0-9a-f]{64}").isNotEqualTo(c.token()).doesNotContain(c.token());
        assertThat(stored).isEqualTo(tokens.hash(c.token()));
    }

    @Test
    void theTokenIsNeverReturnedAgain() throws Exception {
        Created c = create("Secret kept");
        for (String sent : new String[] {c.token(), null, "wrong"}) {
            String body = getAs(c.code(), sent).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(c.token()).doesNotContain("hostToken").doesNotContain("host_token");
        }
        String patched = patchAs(c.code(), c.token(), Map.of("name", "Renamed")).andReturn().getResponse().getContentAsString();
        assertThat(patched).doesNotContain(c.token()).doesNotContain("hostToken");
    }

    // ---- isHost ----

    @Test
    void isHostIsTrueOnlyForTheRightToken() throws Exception {
        Created mine = create("Mine");
        Created other = create("Other");

        getAs(mine.code(), mine.token()).andExpect(jsonPath("$.isHost").value(true));
        getAs(mine.code(), null).andExpect(jsonPath("$.isHost").value(false));
        getAs(mine.code(), "wrong-token").andExpect(jsonPath("$.isHost").value(false));
        getAs(mine.code(), "").andExpect(jsonPath("$.isHost").value(false));
        getAs(mine.code(), other.token()).andExpect(jsonPath("$.isHost").value(false)); // another exchange's token
    }

    @Test
    void exchangesWithoutATokenHaveNoHost() throws Exception {
        // the seeded exchange, and one saved without a token, like exchanges created before host tokens existed
        getAs("XMAS2026", "anything").andExpect(jsonPath("$.isHost").value(false));
        exchanges.save(new Exchange("NOHOST22", "No host"));
        getAs("NOHOST22", "anything").andExpect(jsonPath("$.isHost").value(false));
        getAs("NOHOST22", "").andExpect(jsonPath("$.isHost").value(false));
        patchAs("NOHOST22", "anything", Map.of("name", "Hijack")).andExpect(status().isForbidden());
        assertThat(exchanges.findByCode("NOHOST22").orElseThrow().getName()).isEqualTo("No host");
    }

    @Test
    void tokenMatchingRules() {
        String token = tokens.generate();
        String hash = tokens.hash(token);
        assertThat(tokens.matches(token, hash)).isTrue();
        assertThat(tokens.matches("  " + token + " ", hash)).isTrue(); // stray header spaces
        assertThat(tokens.matches(token + "x", hash)).isFalse();
        assertThat(tokens.matches(null, hash)).isFalse();
        assertThat(tokens.matches("", hash)).isFalse();
        assertThat(tokens.matches(token, null)).isFalse();
        assertThat(tokens.matches(null, null)).isFalse();
    }

    // ---- changing settings ----

    @Test
    void theHostCanChangeEverySetting() throws Exception {
        Created c = create("Before");
        patchAs(c.code(), c.token(), Map.of("name", "After", "description", "New text", "organizerName", "Pat", "listed", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("After"))
                .andExpect(jsonPath("$.description").value("New text"))
                .andExpect(jsonPath("$.organizerName").value("Pat"))
                .andExpect(jsonPath("$.listed").value(false))
                .andExpect(jsonPath("$.isHost").value(true))
                .andExpect(jsonPath("$.code").value(c.code()));

        Exchange saved = exchanges.findByCode(c.code()).orElseThrow();
        assertThat(saved.getName()).isEqualTo("After");
        assertThat(saved.getDescription()).isEqualTo("New text");
        assertThat(saved.getCreatedByName()).isEqualTo("Pat");
        assertThat(saved.isListed()).isFalse();
    }

    @Test
    void fieldsThatAreLeftOutAreNotChanged() throws Exception {
        Created c = create("Keep me");
        patchAs(c.code(), c.token(), Map.of("description", "Only this")).andExpect(status().isOk());
        patchAs(c.code(), c.token(), "{}").andExpect(status().isOk());

        Exchange saved = exchanges.findByCode(c.code()).orElseThrow();
        assertThat(saved.getName()).isEqualTo("Keep me");
        assertThat(saved.getCreatedByName()).isEqualTo("Zach");
        assertThat(saved.isListed()).isTrue();
        assertThat(saved.getDescription()).isEqualTo("Only this");
    }

    @Test
    void anEmptyDescriptionRemovesIt() throws Exception {
        Created c = create("Describe");
        patchAs(c.code(), c.token(), Map.of("description", "Something")).andExpect(status().isOk());
        patchAs(c.code(), c.token(), Map.of("description", "   ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist());
        assertThat(exchanges.findByCode(c.code()).orElseThrow().getDescription()).isNull();
    }

    @Test
    void textIsTrimmed() throws Exception {
        Created c = create("Trim");
        patchAs(c.code(), c.token(), Map.of("name", "  Padded  ", "organizerName", " Pat ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Padded")).andExpect(jsonPath("$.organizerName").value("Pat"));
    }

    @Test
    void invalidValuesAreRejectedPerFieldAndNothingChanges() throws Exception {
        Created c = create("Valid name");
        patchAs(c.code(), c.token(), Map.of("name", "   ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.startsWith("name:")));
        patchAs(c.code(), c.token(), Map.of("name", "x".repeat(201))).andExpect(status().isBadRequest());
        patchAs(c.code(), c.token(), Map.of("description", "x".repeat(1001))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.startsWith("description:")));
        patchAs(c.code(), c.token(), Map.of("organizerName", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.startsWith("organizerName:")));
        patchAs(c.code(), c.token(), Map.of("organizerName", "x".repeat(101))).andExpect(status().isBadRequest());
        // one bad field means none of the good ones are applied either
        patchAs(c.code(), c.token(), Map.of("name", "Fine", "organizerName", " ")).andExpect(status().isBadRequest());

        Exchange saved = exchanges.findByCode(c.code()).orElseThrow();
        assertThat(saved.getName()).isEqualTo("Valid name");
        assertThat(saved.getCreatedByName()).isEqualTo("Zach");
    }

    @Test
    void malformedJsonIs400() throws Exception {
        Created c = create("Json");
        patchAs(c.code(), c.token(), "{not json").andExpect(status().isBadRequest());
    }

    @Test
    void makingAnExchangePrivateOrPublicMovesItOutOfAndIntoTheList() throws Exception {
        Created c = create("Visibility zq8");
        String url = "/api/exchanges";
        mvc.perform(get(url).param("query", "visibility zq8")).andExpect(jsonPath("$.totalItems").value(1));

        patchAs(c.code(), c.token(), Map.of("listed", false)).andExpect(status().isOk());
        mvc.perform(get(url).param("query", "visibility zq8")).andExpect(jsonPath("$.totalItems").value(0));
        getAs(c.code(), null).andExpect(status().isOk()); // still reachable by code

        patchAs(c.code(), c.token(), Map.of("listed", true)).andExpect(status().isOk());
        mvc.perform(get(url).param("query", "visibility zq8")).andExpect(jsonPath("$.totalItems").value(1));
    }

    // ---- who may change it ----

    @Test
    void withoutTheRightTokenNothingCanBeChanged() throws Exception {
        Created mine = create("Protected");
        Created other = create("Someone else's");
        Map<String, Object> change = Map.of("name", "Hijacked", "listed", false);

        patchAs(mine.code(), null, change).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only the host of this exchange can change it"));
        patchAs(mine.code(), "wrong", change).andExpect(status().isForbidden());
        patchAs(mine.code(), "", change).andExpect(status().isForbidden());
        patchAs(mine.code(), other.token(), change).andExpect(status().isForbidden());

        Exchange saved = exchanges.findByCode(mine.code()).orElseThrow();
        assertThat(saved.getName()).isEqualTo("Protected");
        assertThat(saved.isListed()).isTrue();
    }

    @Test
    void anUnknownExchangeIs404() throws Exception {
        patchAs("NOPE123", "token", Map.of("name", "x")).andExpect(status().isNotFound());
    }

    // ---- browsers ----

    @Test
    void browsersMaySendTheHostTokenAndUsePatch() throws Exception {
        mvc.perform(options("/api/exchanges/ABC1234")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "x-host-token,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsStringIgnoringCase("X-Host-Token")))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")));
    }
}
