package com.bookexchange.link;

import java.net.URI;

/**
 * Opens a submitted book link to see whether the page exists. Implementations must never fetch anything
 * outside the allowed hosts and must never throw: any trouble is {@link LinkStatus#UNVERIFIED}.
 */
public interface LinkChecker {

    LinkStatus check(URI link);
}
