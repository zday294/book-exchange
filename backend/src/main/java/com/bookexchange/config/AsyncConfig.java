package com.bookexchange.config;

import com.bookexchange.validation.AmazonProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
@EnableConfigurationProperties(AmazonProperties.class)
public class AsyncConfig {
}
