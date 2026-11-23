package com.bookexchange;

import com.bookexchange.support.PostgresTestConfig;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
abstract class AbstractIntegrationTest {
}
