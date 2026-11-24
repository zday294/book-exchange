package com.bookexchange.exchange;

import com.bookexchange.domain.ExchangeMode;

/** Published when a host switches an exchange to a different mode (for the live updates in #15). */
public record ExchangeModeChangedEvent(String exchangeCode, ExchangeMode mode) {
}
