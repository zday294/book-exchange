package com.bookexchange.error;

import java.util.List;
import org.springframework.http.HttpStatus;

/** An expected, client-facing failure (404, 409, ...) that maps to the standard error body. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public ApiException(HttpStatus status, String message, List<String> details) {
        super(message);
        this.status = status;
        this.details = List.copyOf(details);
    }

    /** Specific problems for the body's "details" (for example "bookUrl: ..."); empty for most errors. */
    public List<String> getDetails() {
        return details;
    }

    /** The same shape as a failed field validation: "Validation failed" with one "field: message" entry. */
    public static ApiException invalidField(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Validation failed", List.of(field + ": " + message));
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }
}
