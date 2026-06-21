package com.scaler.typeahead.exception;

import java.time.Instant;

/**
 * Uniform error body returned for all handled exceptions.
 *
 * @param timestamp when the error occurred
 * @param status    HTTP status code
 * @param error     short reason phrase
 * @param message   human-readable detail
 * @param path      request path
 */
public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}
