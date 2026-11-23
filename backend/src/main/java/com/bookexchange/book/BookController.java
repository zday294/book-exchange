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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private static final String ERR_409 =
            "{\"error\": \"This exchange is closed and no longer accepts books\", \"details\": []}";

    private final BookService service;

    public BookController(BookService service) {
        this.service = service;
    }

    @Operation(
            summary = "Submit a book to an exchange",
            description = "Adds an unclaimed book to the exchange's pile. A cover image is looked up "
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
    @ApiResponse(responseCode = "409", description = "The exchange is closed and no longer accepts books",
            content = @Content(schema = @Schema(implementation = ApiError.class),
                    examples = @ExampleObject(value = ERR_409)))
    @PostMapping
    public ResponseEntity<SubmittedBookResponse> submit(
            @Parameter(description = "Short shareable code of the exchange", example = "XMAS2026")
            @PathVariable String exchangeCode, @Valid @RequestBody SubmitBookRequest request) {
        BookSubmission book = service.submit(exchangeCode, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(book.getId()).toUri();
        return ResponseEntity.created(location).body(SubmittedBookResponse.from(book));
    }
}
