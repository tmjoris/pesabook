package com.pesabook.api;

import java.time.Instant;
import java.util.List;

/**
 * A single error shape for every failure, so a caller never has to guess.
 */
public record ApiError(String error, String message, List<String> details, Instant at) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, List.of(), Instant.now());
    }

    public static ApiError of(String error, String message, List<String> details) {
        return new ApiError(error, message, details, Instant.now());
    }
}
