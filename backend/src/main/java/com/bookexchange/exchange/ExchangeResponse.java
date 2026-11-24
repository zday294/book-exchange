package com.bookexchange.exchange;

import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Public details of an exchange. Contains counts only, never any book's hidden data. */
@Schema(description = "An exchange and how full its pile is. Counts only; no book details.")
public record ExchangeResponse(
        @Schema(description = "Short shareable code", example = "K7M2PXA") String code,
        @Schema(example = "Office Christmas 2026") String name,
        @Schema(example = "Bring a book under $20", nullable = true) String description,
        @Schema(example = "Zach", nullable = true) String organizerName,
        @Schema(description = "Whether it appears in the public list") boolean listed,
        ExchangeStatus status,
        @Schema(description = "Books submitted so far", example = "12") long bookCount,
        @Schema(description = "How many of them have been claimed", example = "5") long claimedCount,
        @Schema(example = "2026-11-24T14:02:03Z") Instant createdAt) {

    public static ExchangeResponse of(Exchange e, long bookCount, long claimedCount) {
        return new ExchangeResponse(e.getCode(), e.getName(), e.getDescription(), e.getCreatedByName(),
                e.isListed(), e.getStatus(), bookCount, claimedCount, e.getCreatedAt());
    }
}
