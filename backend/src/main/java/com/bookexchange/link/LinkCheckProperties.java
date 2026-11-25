package com.bookexchange.link;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for checking that a submitted book link is not a dead page (app.link-check.*).
 *
 * @param cacheMinutes how long a definite answer (page exists / page not found) is remembered per exact link;
 *                     0 turns the cache off. "Couldn't tell" is never remembered.
 */
@ConfigurationProperties(prefix = "app.link-check")
public record LinkCheckProperties(boolean enabled, int connectTimeoutMs, int totalTimeoutMs, int maxRedirects,
                                  int cacheMinutes) {

    public LinkCheckProperties {
        connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 2000;
        totalTimeoutMs = totalTimeoutMs > 0 ? totalTimeoutMs : 4000;
        maxRedirects = maxRedirects >= 0 ? maxRedirects : 5;
        cacheMinutes = Math.max(cacheMinutes, 0);
    }
}
