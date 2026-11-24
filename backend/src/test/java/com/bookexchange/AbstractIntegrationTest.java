package com.bookexchange;

import com.bookexchange.support.PostgresTestConfig;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
// Tests never call the real cover source; the cover tests turn this back on with a stubbed CoverHttp.
@TestPropertySource(properties = "app.cover.enabled=false")
abstract class AbstractIntegrationTest {
}
