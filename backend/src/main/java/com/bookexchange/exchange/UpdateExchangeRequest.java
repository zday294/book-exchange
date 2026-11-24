package com.bookexchange.exchange;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Changes to an exchange's settings. Every field is optional: a field that is left out (or null) is not
 * changed. Text is trimmed. To remove the description, send an empty string.
 */
@Schema(description = "Settings to change. Fields you leave out are not changed; send an empty description to remove it.")
public record UpdateExchangeRequest(
        @Schema(example = "Office Christmas 2026") @Size(max = 200)
        @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Schema(description = "An empty string removes the description", example = "Bring a book under $20")
        @Size(max = 1000) String description,
        @Schema(description = "Name of the host, shown with the exchange", example = "Zach") @Size(max = 100)
        @Pattern(regexp = ".*\\S.*", message = "must not be blank") String organizerName,
        @Schema(description = "Show in the public list (true) or join by link/code only (false)") Boolean listed) {

    public UpdateExchangeRequest {
        name = trim(name);
        description = trim(description);
        organizerName = trim(organizerName);
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }
}
