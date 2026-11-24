package com.bookexchange.exchange;

import com.bookexchange.domain.ExchangeMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "The mode to switch the exchange to")
public record ChangeModeRequest(
        @Schema(description = "SUBMIT: people submit books (no claiming). CLAIM: people claim books (no submitting).",
                example = "CLAIM") @NotNull ExchangeMode mode) {
}
