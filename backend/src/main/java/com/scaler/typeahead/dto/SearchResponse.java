package com.scaler.typeahead.dto;

/**
 * Response body for {@code POST /search} - the "dummy search" response required
 * by the assignment. The query is echoed back so the UI can display what was
 * searched; {@code accepted} is true once the event is durably enqueued onto
 * the Redis Stream (it does NOT mean the count has been flushed to Postgres yet
 * - that happens asynchronously via batch writes).
 */
public record SearchResponse(String message, String query, boolean accepted) {

    public static SearchResponse searched(String query) {
        return new SearchResponse("Searched", query, true);
    }
}
