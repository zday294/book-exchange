package com.bookexchange.exchange;

import com.bookexchange.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/exchanges")
@Tag(name = "Exchanges", description = "Create and look up book exchanges")
public class ExchangeController {

    private final ExchangeService service;

    public ExchangeController(ExchangeService service) {
        this.service = service;
    }

    @Operation(summary = "Create an exchange",
            description = "Creates an OPEN exchange and generates its short, unambiguous code (no 0/O/1/I). "
                    + "Books can be submitted to it immediately. Anyone can create one (there is no sign-in yet).")
    @ApiResponse(responseCode = "201", description = "Created; the body includes the generated `code`",
            headers = @Header(name = "Location", description = "URL of the new exchange",
                    schema = @Schema(example = "http://localhost:8080/api/exchanges/K7M2PXA")))
    @ApiResponse(responseCode = "400",
            description = "Validation failed (name/organizerName required, length limits). Every problem is "
                    + "listed in `details`, prefixed with the field name.",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = @ExampleObject(value = "{\"error\": \"Validation failed\", \"details\": "
                            + "[\"name: must not be blank\", \"organizerName: must not be blank\"]}")))
    @PostMapping
    public ResponseEntity<ExchangeResponse> create(@Valid @RequestBody CreateExchangeRequest request) {
        ExchangeResponse created = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{code}").buildAndExpand(created.code()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Get an exchange",
            description = "Details of one exchange, found by its code even when it is not listed publicly, "
                    + "with the number of books and how many are claimed.")
    @ApiResponse(responseCode = "200", description = "The exchange")
    @ApiResponse(responseCode = "404", description = "No exchange with that code",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = @ExampleObject(value = "{\"error\": \"Exchange not found\", \"details\": []}")))
    @GetMapping("/{exchangeCode}")
    public ExchangeResponse get(
            @Parameter(description = "Short shareable code of the exchange", example = "XMAS2026")
            @PathVariable String exchangeCode) {
        return service.get(exchangeCode);
    }
}
