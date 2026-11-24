package com.bookexchange.book;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for claiming a book. The name is trimmed before validation. */
@Schema(description = "Who is claiming the book. The name is trimmed before validation.")
public record ClaimBookRequest(
        @Schema(description = "Name of the person claiming the book", example = "Alex")
        @NotBlank @Size(max = 100) String participantName) {

    public ClaimBookRequest {
        participantName = participantName == null ? null : participantName.strip();
    }
}
