package com.scaler.typeahead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The single source-of-truth row for a search query and its all-time count.
 *
 * <p>Design notes (viva):
 * <ul>
 *   <li>The {@code query} text itself is the primary key. Search queries are
 *       naturally unique by their normalized text, so a surrogate id would only
 *       add a second index to maintain. Using the text as PK also lets us do a
 *       cheap upsert keyed on the text during batch flush.</li>
 *   <li>{@code query_norm} stores the lower-cased, trimmed form actually used
 *       for prefix matching, so case-insensitive lookups hit a plain B-tree
 *       index instead of forcing {@code LOWER(query)} (which would be
 *       un-indexable without a functional index).</li>
 *   <li>The compound index {@code (query_norm, count DESC)} is what makes
 *       "prefix match ordered by popularity, limit 10" an index-range scan.</li>
 * </ul>
 */
@Entity
@Table(
        name = "search_query",
        indexes = {
                // Supports: WHERE query_norm LIKE 'prefix%' ORDER BY count DESC
                @Index(name = "idx_query_norm", columnList = "query_norm"),
                // Supports trending version 1 (sort by all-time count)
                @Index(name = "idx_count", columnList = "count")
        }
)
public class SearchQuery {

    /** Original (display) query text, also the natural primary key. */
    @Id
    @Column(name = "query", nullable = false, length = 512)
    private String query;

    /** Normalized (lower-cased, trimmed) text used for prefix matching. */
    @Column(name = "query_norm", nullable = false, length = 512)
    private String queryNorm;

    /** All-time search count for this query. */
    @Column(name = "count", nullable = false)
    private long count;

    /** Last time this query was searched (used by recency ranking / audits). */
    @Column(name = "last_searched_at")
    private Instant lastSearchedAt;

    /** When the row was first created. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SearchQuery() {
    }

    public SearchQuery(String query, String queryNorm, long count, Instant lastSearchedAt, Instant createdAt) {
        this.query = query;
        this.queryNorm = queryNorm;
        this.count = count;
        this.lastSearchedAt = lastSearchedAt;
        this.createdAt = createdAt;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getQueryNorm() {
        return queryNorm;
    }

    public void setQueryNorm(String queryNorm) {
        this.queryNorm = queryNorm;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public Instant getLastSearchedAt() {
        return lastSearchedAt;
    }

    public void setLastSearchedAt(Instant lastSearchedAt) {
        this.lastSearchedAt = lastSearchedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
