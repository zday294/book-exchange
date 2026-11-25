package com.bookexchange.config;

import com.bookexchange.cover.CoverProperties;
import com.bookexchange.validation.BookLinkProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({BookLinkProperties.class, CoverProperties.class})
public class AsyncConfig {
}
