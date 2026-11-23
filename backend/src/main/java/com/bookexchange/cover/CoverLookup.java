package com.bookexchange.cover;

/**
 * Finds and stores a cover image for a submitted book. Called asynchronously after the book is
 * committed, so implementations may be slow or fail without affecting the submission.
 * The real Amazon/ISBN-based lookup is a separate issue; until then {@link NoOpCoverLookup}
 * leaves covers empty (the UI shows a placeholder).
 */
public interface CoverLookup {

    void resolveCover(Long bookId);
}
