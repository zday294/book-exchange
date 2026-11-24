package com.bookexchange.exchange;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One page of the public exchange list")
public record ExchangeListResponse(
        @Schema(description = "The exchanges on this page") List<ExchangeSummary> items,
        @Schema(description = "This page, starting at 0", example = "0") int page,
        @Schema(description = "Page size that was used", example = "12") int size,
        @Schema(description = "How many exchanges match in total", example = "37") long totalItems,
        @Schema(description = "How many pages there are (0 when nothing matches)", example = "4") int totalPages) {
}
