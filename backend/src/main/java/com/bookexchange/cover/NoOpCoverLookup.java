package com.bookexchange.cover;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Used when the lookup is switched off (app.cover.enabled=false): books simply have no cover. */
@Component
@ConditionalOnProperty(prefix = "app.cover", name = "enabled", havingValue = "false")
public class NoOpCoverLookup implements CoverLookup {

    @Override
    public void resolveCover(Long bookId) {
        // intentionally nothing
    }
}
