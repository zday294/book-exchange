package com.bookexchange;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookexchange.link.LinkChecker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** LINK_CHECK_ENABLED=false (the default for every other test): the checker is never asked. */
class LinkCheckDisabledTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean LinkChecker checker;

    @Test
    void submittingDoesNotCheckTheLink() throws Exception {
        jdbc.update("delete from book_submission");
        mvc.perform(post("/api/exchanges/XMAS2026/books").contentType(MediaType.APPLICATION_JSON).content(
                "{\"submitterName\":\"Zach\",\"bookTitle\":\"T\",\"bookUrl\":\"https://www.amazon.com/dp/0547928221\",\"wrappingPaper\":\"candy-cane\"}"))
                .andExpect(status().isCreated());
        verifyNoInteractions(checker);
    }
}
