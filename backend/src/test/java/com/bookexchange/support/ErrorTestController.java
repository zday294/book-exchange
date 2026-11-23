package com.bookexchange.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints that trigger errors so the error format can be verified. */
@RestController
public class ErrorTestController {

    public record Body(@NotBlank String name) {}

    @PostMapping("/test/validate")
    public void validate(@Valid @RequestBody Body body) {
    }

    @GetMapping("/test/boom")
    public void boom() {
        throw new IllegalStateException("secret internal detail");
    }
}
