package com.bookexchange.cover;

import java.math.BigDecimal;

/** Published when the cover lookup has worked out a book's cover proportions (width / height, 2 decimals). */
public record BookShapeKnownEvent(Long bookId, String exchangeCode, BigDecimal coverAspectRatio) {
}
