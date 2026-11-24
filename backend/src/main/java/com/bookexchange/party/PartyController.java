package com.bookexchange.party;

import com.bookexchange.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exchanges/{exchangeCode}/party")
@Tag(name = "Party", description = "A host-run queue of turns: one person claims at a time")
public class PartyController {

    private static final String ERR_403 = "{\"error\": \"Only the host of this exchange can change it\", \"details\": []}";
    private static final String ERR_404 = "{\"error\": \"Exchange not found\", \"details\": []}";

    private final PartyService party;

    public PartyController(PartyService party) {
        this.party = party;
    }

    @Operation(summary = "See the party",
            description = "The queue (people who had their turn, then whose turn it is, then those waiting in order), "
                    + "who is online, and how many seconds are left of the current turn (by the server's clock). "
                    + "When no party is running, `active` is false and `eligible` lists who is online and could be queued. "
                    + "The same for everyone; names only.")
    @ApiResponse(responseCode = "200", description = "The party as it is now")
    @ApiResponse(responseCode = "404", description = "No exchange with that code",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = ERR_404)))
    @GetMapping
    public PartyState get(
            @Parameter(description = "Short shareable code of the exchange", example = "K7M2PXA")
            @PathVariable String exchangeCode) {
        return party.state(exchangeCode);
    }

    @Operation(summary = "Start the party (host only)",
            description = "Switches the exchange to party mode (from submit or claim mode) and puts everyone who is "
                    + "online and hasn't claimed a book yet into the queue in a random order. The first person's turn "
                    + "starts at once, and lasts `app.party.turn-seconds` (default 120). In party mode only the person "
                    + "whose turn it is can claim, and nobody can submit.")
    @ApiResponse(responseCode = "200", description = "The party that was started")
    @ApiResponse(responseCode = "403", description = "Missing or wrong host token",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = ERR_403)))
    @ApiResponse(responseCode = "404", description = "No exchange with that code",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = ERR_404)))
    @ApiResponse(responseCode = "409",
            description = "A party is already running, the exchange is closed, there are no presents left, or nobody is online",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(
                    value = "{\"error\": \"Nobody is online who can still claim a present. Ask everyone to open the "
                            + "exchange page, then try again.\", \"details\": []}")))
    @PostMapping("/start")
    public PartyState start(
            @PathVariable String exchangeCode,
            @Parameter(description = "The host token returned when the exchange was created", required = true)
            @RequestHeader(value = "X-Host-Token", required = false) String hostToken) {
        return party.start(exchangeCode, hostToken);
    }

    @Operation(summary = "Skip the current person (host only)",
            description = "The person whose turn it is goes to the end of the queue (they are not removed) and the next "
                    + "person's turn starts.")
    @ApiResponse(responseCode = "200", description = "The party after the skip (or its end, if a whole round passed without a claim)")
    @ApiResponse(responseCode = "403", description = "Missing or wrong host token",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = ERR_403)))
    @ApiResponse(responseCode = "404", description = "No exchange with that code",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = ERR_404)))
    @ApiResponse(responseCode = "409", description = "No party is running, or nobody has the turn",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = @ExampleObject(value = "{\"error\": \"There is no party running\", \"details\": []}")))
    @PostMapping("/skip")
    public PartyState skip(
            @PathVariable String exchangeCode,
            @RequestHeader(value = "X-Host-Token", required = false) String hostToken) {
        return party.skip(exchangeCode, hostToken);
    }

    @Operation(summary = "End the party (host only)",
            description = "Ends the party and returns the exchange to claim mode, so the remaining presents can still be "
                    + "claimed by anyone. Claims already made stay. A party also ends by itself when every present is claimed, "
                    + "or when a whole round passes without anyone claiming.")
    @ApiResponse(responseCode = "200", description = "The ended party (`active` false, with `endedReason`)")
    @ApiResponse(responseCode = "403", description = "Missing or wrong host token",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = ERR_403)))
    @ApiResponse(responseCode = "404", description = "No exchange with that code",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = ERR_404)))
    @ApiResponse(responseCode = "409", description = "No party is running",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = @ExampleObject(value = "{\"error\": \"There is no party running\", \"details\": []}")))
    @PostMapping("/end")
    public PartyState end(
            @PathVariable String exchangeCode,
            @RequestHeader(value = "X-Host-Token", required = false) String hostToken) {
        return party.end(exchangeCode, hostToken);
    }
}
