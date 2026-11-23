package com.bookexchange.error;

import java.util.List;

/** Standard JSON error body: {"error": "...", "details": [...]}. */
public record ApiError(String error, List<String> details) {

    public static ApiError of(String error) {
        return new ApiError(error, List.of());
    }
}
