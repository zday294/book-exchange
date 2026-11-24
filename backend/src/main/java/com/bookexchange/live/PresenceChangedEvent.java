package com.bookexchange.live;

/** Someone with a name opened a live connection to an exchange (so they are online there). */
public record PresenceChangedEvent(String exchangeCode, String name) {
}
