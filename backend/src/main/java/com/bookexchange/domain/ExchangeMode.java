package com.bookexchange.domain;

/**
 * What an open exchange is currently for. The two are exclusive: while people are bringing books nobody can
 * claim, and once claiming starts nobody can submit. (A "party" mode with turns comes later and just adds a value.)
 */
public enum ExchangeMode {
    /** Participants submit their books. Claiming is not allowed. */
    SUBMIT,
    /** Participants claim books. Submitting is not allowed. */
    CLAIM
}
