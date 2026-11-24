package com.bookexchange.cover;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the ASIN (Amazon's 10-character product id) out of the common Amazon product link shapes. */
public final class AsinParser {

    private static final Pattern PATH = Pattern.compile(
            "/(?:dp|gp/product|gp/aw/d|gp/offer-listing|exec/obidos/ASIN|exec/obidos/tg/detail/-|product)/"
                    + "([A-Za-z0-9]{10})(?![A-Za-z0-9])");

    private AsinParser() {
    }

    public static Optional<String> parse(URI uri) {
        String path = uri.getPath();
        if (path == null) {
            return Optional.empty();
        }
        Matcher m = PATH.matcher(path);
        return m.find() ? Optional.of(m.group(1).toUpperCase(Locale.ROOT)) : Optional.empty();
    }

    public static Optional<String> parse(String url) {
        try {
            return parse(URI.create(url));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
