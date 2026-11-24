package com.bookexchange.exchange;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for creating an exchange. Text fields are trimmed; a blank description becomes null. */
@Schema(description = "A new exchange. Text fields are trimmed before validation.")
public record CreateExchangeRequest(
        @Schema(example = "Office Christmas 2026") @NotBlank @Size(max = 200) String name,
        @Schema(description = "Optional", example = "Bring a book under $20") @Size(max = 1000) String description,
        @Schema(description = "Name of the person creating the exchange", example = "Zach")
        @NotBlank @Size(max = 100) String organizerName,
        @Schema(description = "Show in the public list (true, the default) or join by link/code only (false)",
                example = "true", defaultValue = "true") Boolean listed) {

    public CreateExchangeRequest {
        name = trim(name);
        organizerName = trim(organizerName);
        description = trim(description);
        if (description != null && description.isEmpty()) {
            description = null;
        }
    }

    public boolean isListed() {
        return listed == null || listed;
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }
}
