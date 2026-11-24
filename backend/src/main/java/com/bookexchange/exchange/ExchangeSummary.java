package com.bookexchange.exchange;

import com.bookexchange.domain.ExchangeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** One exchange in the public list. Counts only: nothing about any book, and no organizer details. */
@Schema(description = "An exchange as shown in the public list. Counts only; no book details.")
public record ExchangeSummary(
        @Schema(description = "Short shareable code", example = "K7M2PXA") String code,
        @Schema(example = "Office Christmas 2026") String name,
        @Schema(example = "Bring a book under $20", nullable = true) String description,
        ExchangeStatus status,
        @Schema(description = "Books submitted so far", example = "12") long bookCount,
        @Schema(description = "How many of them have been claimed", example = "5") long claimedCount,
        @Schema(example = "2026-11-24T14:02:03Z") Instant createdAt) {
}
