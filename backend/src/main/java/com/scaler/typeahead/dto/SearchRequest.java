package com.scaler.typeahead.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /search}.
 *
 * <p>Validation is enforced via Bean Validation; a blank or oversized query is
 * rejected with HTTP 400 by {@code GlobalExceptionHandler}.
 */
public record SearchRequest(
        @NotBlank(message = "query must not be blank")
        @Size(max = 512, message = "query must be at most 512 characters")
        String query
) {
}
