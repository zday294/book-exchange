package com.bookexchange.validation;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hosts a submitted book link may point to. A host matches if it equals an entry or is a
 * subdomain of it (so "amazon.com" also allows "www.amazon.com", but not "evilamazon.com").
 */
@ConfigurationProperties(prefix = "app.amazon")
public record AmazonProperties(List<String> allowedHosts) {

    public AmazonProperties {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
    }
}
