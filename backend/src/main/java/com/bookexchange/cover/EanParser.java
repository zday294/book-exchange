package com.bookexchange.cover;

import java.net.URI;
import java.util.Optional;

/**
 * Reads the book number out of a Barnes &amp; Noble link: product links carry it as the {@code ean}
 * query parameter (an ISBN-13), e.g. {@code .../w/the-hobbit/1100054405?ean=9780547928227}.
 * Only a valid ISBN-13 (checksum checked) counts; anything else, or a link without the parameter, gives nothing.
 */
public final class EanParser {

    private EanParser() {
    }

    public static Optional<String> parse(URI uri) {
        String query = uri.getRawQuery();
        if (query == null) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equalsIgnoreCase("ean")) {
                String value = pair.substring(eq + 1).trim();
                if (Isbn.isValidIsbn13(value)) {
                    return Optional.of(value); // the first valid one wins; a malformed one is skipped
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<String> parse(String url) {
        try {
            return parse(URI.create(url));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
