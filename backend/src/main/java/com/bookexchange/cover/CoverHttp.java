package com.bookexchange.cover;

import java.net.URI;
import java.util.Optional;

/**
 * The only way the cover lookup touches the network. Implementations must never fetch anything
 * that is not on the allow-lists, and must never throw for a plain "not found" or "too slow".
 */
public interface CoverHttp {

    /**
     * Follows the redirects of a short Amazon link (a.co, amzn.to) until a URL that contains an ASIN,
     * without downloading product pages. Empty if it cannot be resolved safely.
     */
    Optional<URI> resolveShortLink(URI shortLink);

    /** Downloads a cover image from the cover source. Empty unless it is a small, genuine image. */
    Optional<byte[]> fetchImage(URI imageUrl);
}
