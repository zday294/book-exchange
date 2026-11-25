package com.bookexchange.cover;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the cover lookup (see backend/README.md).
 *
 * @param enabled           turn the whole lookup off (books then simply have no cover)
 * @param imageUrlTemplate  where covers come from; %s is the ISBN-10 or ISBN-13
 * @param allowedImageHosts hosts the cover source may serve from or redirect to (subdomains allowed)
 * @param totalTimeoutMs    longest one lookup step may take, redirects and download included
 * @param maxImageBytes     largest image accepted
 * @param maxRedirects      redirects followed per step
 */
@ConfigurationProperties(prefix = "app.cover")
public record CoverProperties(
        boolean enabled,
        String imageUrlTemplate,
        List<String> allowedImageHosts,
        int connectTimeoutMs,
        int totalTimeoutMs,
        int maxImageBytes,
        int maxRedirects) {

    public CoverProperties {
        allowedImageHosts = allowedImageHosts == null ? List.of() : List.copyOf(allowedImageHosts);
    }
}
