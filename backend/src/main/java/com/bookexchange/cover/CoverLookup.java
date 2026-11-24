package com.bookexchange.cover;

/**
 * Finds and stores a cover image for a submitted book. Called asynchronously after the book is
 * committed, so implementations may be slow or fail without affecting the submission.
 * See {@link IsbnCoverLookup}; {@link NoOpCoverLookup} is used when the lookup is switched off.
 */
public interface CoverLookup {

    void resolveCover(Long bookId);
}
