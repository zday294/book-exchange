package com.bookexchange.link;

/** What checking a book link found. Only {@link #NOT_FOUND} ever stops a submission. */
public enum LinkStatus {
    /** The page answered with a success status. */
    OK,
    /** The store definitely says the page does not exist (404 or 410). */
    NOT_FOUND,
    /** Anything else (blocked, rate limited, error, timeout, refused for safety): can't tell, so don't block. */
    UNVERIFIED
}
