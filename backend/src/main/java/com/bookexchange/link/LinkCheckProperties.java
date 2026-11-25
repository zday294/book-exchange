package com.bookexchange.link;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for checking that a submitted book link is not a dead page (app.link-check.*). */
@ConfigurationProperties(prefix = "app.link-check")
public record LinkCheckProperties(boolean enabled, int connectTimeoutMs, int totalTimeoutMs, int maxRedirects) {

    public LinkCheckProperties {
        connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 2000;
        totalTimeoutMs = totalTimeoutMs > 0 ? totalTimeoutMs : 4000;
        maxRedirects = maxRedirects >= 0 ? maxRedirects : 5;
    }
}
