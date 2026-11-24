package com.bookexchange.party;

/** Published (inside the transaction that changed it) when the party queue changes, for the live stream. */
public record PartyChangedEvent(String exchangeCode, PartyState state) {
}
