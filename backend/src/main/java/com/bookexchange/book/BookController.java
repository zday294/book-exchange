package com.bookexchange.book;

import com.bookexchange.domain.BookSubmission;
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
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/exchanges/{exchangeCode}/books")
@Tag(name = "Books", description = "Books in an exchange's pile")
public class BookController {

    private static final String ERR_400 = """
            {"error": "Validation failed", "details": [
              "amazonUrl: must be an https link to Amazon (amazon.com, amzn.to or a.co)",
              "wrappingPaper: must be a known wrapping paper"]}""";
    private static final String ERR_404 = "{\"error\": \"Exchange not found\", \"details\": []}";
    private static final String ERR_409_CLOSED =
            "{\"error\": \"This exchange is closed and no longer accepts books\", \"details\": []}";
    private static final String ERR_409_DUPLICATE =
            "{\"error\": \"You have already submitted a book to this exchange\", \"details\": []}";

    private final BookService service;

    public BookController(BookService service) {
        this.service = service;
    }

    @Operation(
            summary = "Submit a book to an exchange",
            description = "Adds an unclaimed book to the exchange's pile. Each person may submit one book per exchange. A cover image is looked up "
                    + "in the background after the book is saved; a failed lookup never affects this call.")
    @ApiResponse(responseCode = "201", description = "Book submitted",
            headers = @Header(name = "Location", description = "URL of the new book",
                    schema = @Schema(example = "http://localhost:8080/api/exchanges/XMAS2026/books/12")))
    @ApiResponse(responseCode = "400",
            description = "Validation failed (missing/blank fields, too long, bad link, unknown wrapping paper) "
                    + "or the body isn't valid JSON. All validation problems are listed in `details`.",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = @ExampleObject(value = ERR_400)))
    @ApiResponse(responseCode = "404", description = "No exchange with that code",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = @ExampleObject(value = ERR_404)))
    @ApiResponse(responseCode = "409",
            description = "The exchange is closed, or this person (name compared case-insensitively, "
                    + "ignoring surrounding spaces) has already submitted a book to it. Nothing is saved.",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = {
                            @ExampleObject(name = "Exchange closed", value = ERR_409_CLOSED),
                            @ExampleObject(name = "Already submitted", value = ERR_409_DUPLICATE)}))
    @PostMapping
    public ResponseEntity<SubmittedBookResponse> submit(
            @Parameter(description = "Short shareable code of the exchange", example = "XMAS2026")
            @PathVariable String exchangeCode, @Valid @RequestBody SubmitBookRequest request) {
        BookSubmission book = service.submit(exchangeCode, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(book.getId()).toUri();
        return ResponseEntity.created(location).body(SubmittedBookResponse.from(book));
    }

    private static final String VIEWER_HELP = "The viewer is identified by the optional `X-Participant-Name` "
            + "header (name only, no authentication). Without it you are an anonymous viewer. Hidden fields are "
            + "omitted, never sent. Unclaimed books show only id, submitter, wrapping paper and `claimed: false`. "
            + "Claimed books also show the claimer, claim time, title and cover. The Amazon link is returned "
            + "only to the claimer (name matched case-insensitively).";
    private static final String ERR_404_BOOK = "{\"error\": \"Book not found\", \"details\": []}";

    @Operation(summary = "List the books in an exchange's pile",
            description = "All books in the exchange, oldest first (stable order). " + VIEWER_HELP)
    @ApiResponse(responseCode = "200", description = "The pile, filtered for the viewer")
    @ApiResponse(responseCode = "404", description = "No exchange with that code",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = @ExampleObject(value = ERR_404)))
    @GetMapping
    public List<BookView> list(
            @Parameter(description = "Short shareable code of the exchange", example = "XMAS2026")
            @PathVariable String exchangeCode,
            @Parameter(description = "Name of the person viewing (optional)", example = "Alex")
            @RequestHeader(value = "X-Participant-Name", required = false) String viewerName) {
        return service.list(exchangeCode, viewerName);
    }

    @Operation(summary = "Get one book", description = VIEWER_HELP)
    @ApiResponse(responseCode = "200", description = "The book, filtered for the viewer")
    @ApiResponse(responseCode = "404", description = "No such exchange, or no such book in that exchange",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = {
                            @ExampleObject(name = "Exchange not found", value = ERR_404),
                            @ExampleObject(name = "Book not found", value = ERR_404_BOOK)}))
    @GetMapping("/{bookId}")
    public BookView get(
            @Parameter(description = "Short shareable code of the exchange", example = "XMAS2026")
            @PathVariable String exchangeCode,
            @Parameter(description = "Id of the book", example = "12") @PathVariable Long bookId,
            @Parameter(description = "Name of the person viewing (optional)", example = "Alex")
            @RequestHeader(value = "X-Participant-Name", required = false) String viewerName) {
        return service.get(exchangeCode, bookId, viewerName);
    }

    private static final String ERR_409_CLAIM_CLOSED =
            "{\"error\": \"This exchange is closed and no longer accepts claims\", \"details\": []}";
    private static final String ERR_409_TAKEN =
            "{\"error\": \"This book has already been claimed\", \"details\": []}";
    private static final String ERR_409_ALREADY_HAVE =
            "{\"error\": \"You have already claimed a book in this exchange\", \"details\": []}";
    private static final String ERR_409_OWN =
            "{\"error\": \"You can't claim a book you submitted yourself\", \"details\": []}";

    @Operation(summary = "Claim a book",
            description = "Claims an unclaimed book for the named participant and returns it in the "
                    + "claimer's view (title, cover, claimer and the Amazon link). Safe under concurrency: "
                    + "of simultaneous claims for one book, or by one person, exactly one succeeds.")
    @ApiResponse(responseCode = "200", description = "Claimed; the body is the claimer's view of the book")
    @ApiResponse(responseCode = "400", description = "Missing, blank or too-long participantName",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = @ExampleObject(value = "{\"error\": \"Validation failed\", "
                            + "\"details\": [\"participantName: must not be blank\"]}")))
    @ApiResponse(responseCode = "404", description = "No such exchange, or no such book in that exchange",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = {
                            @ExampleObject(name = "Exchange not found", value = ERR_404),
                            @ExampleObject(name = "Book not found", value = ERR_404_BOOK)}))
    @ApiResponse(responseCode = "409",
            description = "The claim isn't allowed. Nothing is changed.",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = {
                            @ExampleObject(name = "Book already claimed", value = ERR_409_TAKEN),
                            @ExampleObject(name = "Person already claimed a book", value = ERR_409_ALREADY_HAVE),
                            @ExampleObject(name = "Own book", value = ERR_409_OWN),
                            @ExampleObject(name = "Exchange closed", value = ERR_409_CLAIM_CLOSED)}))
    @PostMapping("/{bookId}/claim")
    public BookView claim(
            @Parameter(description = "Short shareable code of the exchange", example = "XMAS2026")
            @PathVariable String exchangeCode,
            @Parameter(description = "Id of the book", example = "12") @PathVariable Long bookId,
            @Valid @RequestBody ClaimBookRequest request) {
        return service.claim(exchangeCode, bookId, request.participantName());
    }
}
