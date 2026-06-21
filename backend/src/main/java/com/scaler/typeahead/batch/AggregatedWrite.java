package com.scaler.typeahead.batch;

/**
 * One aggregated count update ready to be flushed to the primary store.
 * If the same query was searched {@code delta} times in a batch window, it
 * becomes a single row update instead of {@code delta} writes.
 *
 * @param queryNorm normalized query (matching/aggregation key)
 * @param display   original display text
 * @param delta     number of searches aggregated in this window
 */
public record AggregatedWrite(String queryNorm, String display, long delta) {
}
