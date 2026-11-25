package com.bookexchange.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Standard JSON error body: {"error": "...", "details": [...]}. */
@Schema(description = "Standard error body used by every endpoint")
public record ApiError(
        @Schema(description = "Short, human-readable message", example = "Validation failed") String error,
        @Schema(description = "Specific problems, such as one entry per invalid field; empty when there are none",
                example = "[\"bookUrl: must be an https link to Amazon or Barnes & Noble (amazon.com, amzn.to, a.co, barnesandnoble.com or bn.com)\"]")
        List<String> details) {

    public static ApiError of(String error) {
        return new ApiError(error, List.of());
    }
}
