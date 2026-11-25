package com.bookexchange;

import com.bookexchange.support.PostgresTestConfig;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
// Tests never call the internet: the real cover source and the real link check are off here. The tests for
// those turn them back on with a stubbed CoverHttp / LinkChecker.
@TestPropertySource(properties = {"app.cover.enabled=false", "app.link-check.enabled=false"})
abstract class AbstractIntegrationTest {
}
