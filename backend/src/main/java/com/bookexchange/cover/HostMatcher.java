package com.bookexchange.cover;

import java.util.List;
import java.util.Locale;

final class HostMatcher {

    private HostMatcher() {
    }

    /** True if the host equals an entry or is a subdomain of one ("amazon.com" allows "www.amazon.com", not "evilamazon.com"). */
    static boolean matches(String host, List<String> allowed) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return allowed.stream()
                .map(a -> a.toLowerCase(Locale.ROOT))
                .anyMatch(a -> h.equals(a) || h.endsWith("." + a));
    }
}
