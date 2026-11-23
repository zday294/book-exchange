package com.bookexchange.book;

import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The wrapping paper keys the API accepts. The visual designs live in the front end; these
 * keys must match the front end's list (see the wrapping paper issue).
 */
@Component
public class WrappingPapers {

    private static final Set<String> KEYS = Set.of(
            "candy-cane", "snowflake", "holly", "stars", "plaid", "polka-dot");

    public boolean isKnown(String key) {
        return KEYS.contains(key);
    }
}
