package com.bookexchange.config;

import com.bookexchange.cover.CoverProperties;
import com.bookexchange.validation.AmazonProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({AmazonProperties.class, CoverProperties.class})
public class AsyncConfig {
}
