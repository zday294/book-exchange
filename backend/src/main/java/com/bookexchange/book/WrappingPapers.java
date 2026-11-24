package com.bookexchange.book;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The wrapping paper keys the API accepts. The visual designs live in the front end; these
 * keys must match the front end's list (see the wrapping paper issue).
 */
@Component
public class WrappingPapers {

    private static final List<String> KEY_LIST = List.of(
            "candy-cane", "snowflake", "holly", "stars", "plaid",
            "polka-dot", "sugar-cookie", "christmas-tree", "fireplace", "sleigh");
    private static final Set<String> KEYS = Set.copyOf(KEY_LIST);

    /** All accepted keys, in display order. */
    public List<String> keys() {
        return KEY_LIST;
    }

    public boolean isKnown(String key) {
        return KEYS.contains(key);
    }
}
