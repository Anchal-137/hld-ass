package com.scaler.typeahead.dto;

/**
 * A single suggestion item returned to the UI.
 *
 * @param query the suggested query text (display form)
 * @param count the score used for ordering (all-time count in basic mode, or
 *              the blended recency+frequency score in enhanced mode)
 */
public record SuggestionDto(String query, long count) {
}
