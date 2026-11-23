package com.bookexchange;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(properties = "springdoc.api-docs.enabled=false")
class OpenApiDisabledTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void docsCanBeSwitchedOffWithConfiguration() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    }
}
