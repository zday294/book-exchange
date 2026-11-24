package com.bookexchange.exchange;

import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
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
        @Schema(example = "2026-11-24T14:02:03Z") Instant createdAt,
        @Schema(description = "True only when the request carried the valid host token for this exchange "
                + "(`X-Host-Token` header). Exchanges created before host tokens existed have no host.")
        boolean isHost,
        @Schema(description = "The host token. Returned ONLY in the response to creating the exchange, "
                + "and never again: save it. It is stored hashed.", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL) String hostToken) {

    public static ExchangeResponse of(Exchange e, long bookCount, long claimedCount, boolean isHost) {
        return new ExchangeResponse(e.getCode(), e.getName(), e.getDescription(), e.getCreatedByName(),
                e.isListed(), e.getStatus(), bookCount, claimedCount, e.getCreatedAt(), isHost, null);
    }

    /** The same response with the freshly created host token added (used once, on creation). */
    public ExchangeResponse withHostToken(String token) {
        return new ExchangeResponse(code, name, description, organizerName, listed, status, bookCount,
                claimedCount, createdAt, isHost, token);
    }
}
