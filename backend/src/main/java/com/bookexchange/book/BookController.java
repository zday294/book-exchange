package com.bookexchange.book;

import com.bookexchange.domain.BookSubmission;
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
public class BookController {

    private final BookService service;

    public BookController(BookService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SubmittedBookResponse> submit(
            @PathVariable String exchangeCode, @Valid @RequestBody SubmitBookRequest request) {
        BookSubmission book = service.submit(exchangeCode, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(book.getId()).toUri();
        return ResponseEntity.created(location).body(SubmittedBookResponse.from(book));
    }
}
