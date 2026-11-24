package com.bookexchange.domain;

/**
 * What an open exchange is currently for. They are exclusive: while people are bringing books nobody can claim,
 * and once claiming starts nobody can submit.
 */
public enum ExchangeMode {
    /** Participants submit their books. Claiming is not allowed. */
    SUBMIT,
    /** Participants claim books. Submitting is not allowed. */
    CLAIM,
    /**
     * Claiming, one person at a time: a host-run queue of turns. Submitting is not allowed. The host starts and
     * ends a party with the party endpoints, not by switching the mode directly.
     */
    PARTY
}
