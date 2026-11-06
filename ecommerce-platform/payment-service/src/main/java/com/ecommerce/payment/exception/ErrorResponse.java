package com.ecommerce.payment.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * The one error shape this service returns, for every failure.
 *
 * <p>One envelope, not one per case, because a client that has to branch on response shape to find
 * out what went wrong will branch wrong. {@code fieldErrors} is null-suppressed so a 404 does not
 * carry an empty object that looks like it might have meant something.
 *
 * <p>Duplicated in every service rather than shared (D2). Six copies of a five-field record is a
 * smaller cost than a shared jar that couples six release cycles together.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors
) {
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ErrorResponse of(int status, String error, String message, String path,
                                   Map<String, String> fieldErrors) {
        return new ErrorResponse(Instant.now(), status, error, message, path, fieldErrors);
    }
}
