package com.bookexchange.cover;

import org.springframework.stereotype.Component;

/** Placeholder until the real cover lookup exists. */
@Component
public class NoOpCoverLookup implements CoverLookup {

    @Override
    public void resolveCover(Long bookId) {
        // intentionally nothing
    }
}
