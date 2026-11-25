package com.bookexchange.live;

import com.bookexchange.error.ApiException;
import com.bookexchange.error.ApiError;
import com.bookexchange.repository.ExchangeRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/exchanges/{exchangeCode}/events")
@Tag(name = "Live updates", description = "Server-Sent Events: changes to an exchange as they happen")
public class LiveUpdateController {

    private final ExchangeRepository exchanges;
    private final ExchangeEventHub hub;

    public LiveUpdateController(ExchangeRepository exchanges, ExchangeEventHub hub) {
        this.exchanges = exchanges;
        this.hub = hub;
    }

    @Operation(summary = "Watch an exchange live",
            description = "A long-lived `text/event-stream` (Server-Sent Events) connection for ONE exchange; "
                    + "browsers open it with `EventSource`. Events are sent only after the change is saved, and "
                    + "contain public information only, never a book link:\n\n"
                    + "- `ready`: the connection is open (sent immediately).\n"
                    + "- `book-added`: `{id, submitterName, wrappingPaper}`, a new wrapped present. Nothing about the book.\n"
                    + "- `book-shaped`: `{id, coverAspectRatio}`, the cover lookup finished and the present's proportions are "
                    + "now known (rounded width / height; nothing else about the cover).\n"
                    + "- `book-claimed`: `{id, claimedByName, claimedAt, bookTitle, coverImageUrl}`, a present was claimed "
                    + "(`coverImageUrl` is omitted when unknown).\n"
                    + "- `mode-changed`: `{mode}` (`SUBMIT`, `CLAIM` or `PARTY`).\n"
                    + "- `party-changed`: the party queue: `{active, endedReason?, current?, turnSecondsLeft?, queue:[{name, status, online}], "
                    + "eligible}`. Names and order only.\n\n"
                    + "A comment line (`: keep-alive`) is sent every few seconds so proxies keep the connection open. "
                    + "The server recycles connections after a while; the browser reconnects by itself and should "
                    + "re-read the pile, because events sent while it was away are not replayed.")
    @ApiResponse(responseCode = "200", description = "The event stream",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    examples = @ExampleObject(value = "event: book-added\\ndata: {\"id\":12,\"submitterName\":\"Zach\","
                            + "\"wrappingPaper\":\"candy-cane\"}\\n\\n")))
    @ApiResponse(responseCode = "404", description = "No exchange with that code",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = @ExampleObject(value = "{\"error\": \"Exchange not found\", \"details\": []}")))
    @ApiResponse(responseCode = "503", description = "Too many live connections open right now; try again later")
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter watch(
            @Parameter(description = "Short shareable code of the exchange", example = "XMAS2026")
            @PathVariable String exchangeCode,
            @Parameter(description = "Who is watching (optional). With a name, the viewer counts as online for the "
                    + "party. A browser's EventSource can't send headers, so it goes in the URL.", example = "Alex")
            @RequestParam(required = false) String name) {
        if (exchanges.findByCode(exchangeCode).isEmpty()) {
            throw ApiException.notFound("Exchange not found");
        }
        return hub.subscribe(exchangeCode, name);
    }
}
