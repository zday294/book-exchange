package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.exchange.ExchangeCodeGenerator;
import com.bookexchange.exchange.RandomExchangeCodeGenerator;
import com.bookexchange.repository.ExchangeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Import(CreateExchangeTest.ScriptedCodes.class)
class CreateExchangeTest extends AbstractIntegrationTest {

    /** When non-empty, codes come from here (to force collisions); otherwise the real generator. */
    static final Deque<String> SCRIPT = new ArrayDeque<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedCodes {
        @Bean
        @Primary
        ExchangeCodeGenerator scriptedCodes() {
            RandomExchangeCodeGenerator real = new RandomExchangeCodeGenerator();
            return () -> {
                synchronized (SCRIPT) {
                    return SCRIPT.isEmpty() ? real.next() : SCRIPT.poll();
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ExchangeRepository exchanges;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        SCRIPT.clear();
    }

    private static Map<String, Object> valid() {
        return new HashMap<>(Map.of("name", "Office Christmas 2026", "description", "Bring a book under $20",
                "organizerName", "Zach", "listed", true));
    }

    private ResultActions create(Object body) throws Exception {
        return mvc.perform(post("/api/exchanges").contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : json.writeValueAsString(body)));
    }

    private String createdCode(Object body) throws Exception {
        return json.readTree(create(body).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("code").asText();
    }

    @Test
    void createsAnOpenExchangeWithAGeneratedCode() throws Exception {
        ResultActions r = create(valid()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Office Christmas 2026"))
                .andExpect(jsonPath("$.description").value("Bring a book under $20"))
                .andExpect(jsonPath("$.organizerName").value("Zach"))
                .andExpect(jsonPath("$.listed").value(true))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.bookCount").value(0))
                .andExpect(jsonPath("$.claimedCount").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        String code = json.readTree(r.andReturn().getResponse().getContentAsString()).get("code").asText();
        r.andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/exchanges/" + code)));

        assertThat(code).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{7}");
        Exchange saved = exchanges.findByCode(code).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ExchangeStatus.OPEN);
        assertThat(saved.getCreatedByName()).isEqualTo("Zach");
    }

    @Test
    void codesNeverContainAmbiguousCharacters() {
        RandomExchangeCodeGenerator gen = new RandomExchangeCodeGenerator();
        for (int i = 0; i < 5000; i++) {
            assertThat(gen.next()).hasSize(7).doesNotContainPattern("[01OIL]");
        }
    }

    @Test
    void newExchangeAcceptsBooksImmediately() throws Exception {
        String code = createdCode(valid());
        mvc.perform(post("/api/exchanges/{c}/books", code).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submitterName\":\"Zach\",\"bookTitle\":\"The Hobbit\","
                                + "\"bookUrl\":\"https://www.amazon.com/dp/0547928221\","
                                + "\"wrappingPaper\":\"candy-cane\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/exchanges/{c}", code)).andExpect(jsonPath("$.bookCount").value(1))
                .andExpect(jsonPath("$.claimedCount").value(0));
    }

    @Test
    void optionalFieldsDefaultSensibly() throws Exception {
        create(Map.of("name", "Minimal", "organizerName", "Zach")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.listed").value(true))
                .andExpect(jsonPath("$.description").doesNotExist());
        Map<String, Object> blankDesc = valid();
        blankDesc.put("description", "   ");
        create(blankDesc).andExpect(status().isCreated()).andExpect(jsonPath("$.description").doesNotExist());
    }

    @Test
    void privateExchangeCanBeCreated() throws Exception {
        Map<String, Object> body = valid();
        body.put("listed", false);
        create(body).andExpect(status().isCreated()).andExpect(jsonPath("$.listed").value(false));
    }

    @Test
    void textIsTrimmed() throws Exception {
        Map<String, Object> body = valid();
        body.put("name", "  Padded  ");
        body.put("organizerName", " Zach ");
        create(body).andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Padded"))
                .andExpect(jsonPath("$.organizerName").value("Zach"));
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of("missing name", without("name"), "name"),
                Arguments.of("blank name", with("name", "  "), "name"),
                Arguments.of("name too long", with("name", "x".repeat(201)), "name"),
                Arguments.of("description too long", with("description", "x".repeat(1001)), "description"),
                Arguments.of("missing organizerName", without("organizerName"), "organizerName"),
                Arguments.of("blank organizerName", with("organizerName", ""), "organizerName"),
                Arguments.of("organizerName too long", with("organizerName", "x".repeat(101)), "organizerName"));
    }

    private static Map<String, Object> with(String k, Object v) {
        Map<String, Object> m = valid();
        m.put(k, v);
        return m;
    }

    private static Map<String, Object> without(String k) {
        Map<String, Object> m = valid();
        m.remove(k);
        return m;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    void invalidRequestsAreRejectedPerFieldAndSaveNothing(String name, Map<String, Object> body, String field)
            throws Exception {
        long before = exchanges.count();
        create(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.startsWith(field + ":")));
        assertThat(exchanges.count()).isEqualTo(before);
    }

    @Test
    void allProblemsAreReportedTogether() throws Exception {
        create("{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(2));
    }

    @Test
    void forcedCollisionIsRetriedWithANewCode() throws Exception {
        exchanges.save(new Exchange("TAKEN22", "Existing"));
        SCRIPT.addAll(List.of("TAKEN22", "TAKEN22", "FRESH99"));

        assertThat(createdCode(valid())).isEqualTo("FRESH99");
        assertThat(exchanges.findByCode("TAKEN22").orElseThrow().getName()).isEqualTo("Existing");
    }

    @Test
    void givesUpWithA500IfNoUniqueCodeCanBeFound() throws Exception {
        exchanges.save(new Exchange("STUCK22", "Stuck"));
        for (int i = 0; i < 20; i++) SCRIPT.add("STUCK22");
        create(valid()).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error"));
        SCRIPT.clear();
    }

    @Test
    void manyParallelCreatesAllGetDistinctCodes() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<String>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 24; i++) fs.add(pool.submit(() -> createdCode(valid())));
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (var f : fs) codes.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(codes).hasSize(24);
    }

    @Test
    void getReturnsDetailsAndCounts() throws Exception {
        jdbc.update("delete from book_submission");
        Exchange ex = exchanges.save(new Exchange("COUNTS22", "Counts"));
        jdbc.update("insert into book_submission (exchange_id, submitter_name, book_title, book_url, "
                + "wrapping_paper, claimed_by_name, claimed_at) values (?, 'A', 't', 'https://amazon.com/x', 'stars', 'B', now())", ex.getId());
        jdbc.update("insert into book_submission (exchange_id, submitter_name, book_title, book_url, "
                + "wrapping_paper) values (?, 'C', 't', 'https://amazon.com/y', 'stars')", ex.getId());

        mvc.perform(get("/api/exchanges/COUNTS22")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COUNTS22"))
                .andExpect(jsonPath("$.bookCount").value(2))
                .andExpect(jsonPath("$.claimedCount").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
        jdbc.update("delete from book_submission");
    }

    @Test
    void unlistedExchangeIsStillFoundByCode() throws Exception {
        Map<String, Object> body = valid();
        body.put("listed", false);
        String code = createdCode(body);
        mvc.perform(get("/api/exchanges/{c}", code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.listed").value(false));
    }

    @Test
    void unknownCodeIs404() throws Exception {
        mvc.perform(get("/api/exchanges/NOPE123")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Exchange not found"));
    }
}
