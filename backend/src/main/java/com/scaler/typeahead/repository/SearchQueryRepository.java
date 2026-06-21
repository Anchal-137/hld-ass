package com.scaler.typeahead.repository;

import com.scaler.typeahead.entity.SearchQuery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository over the PRIMARY data store (PostgreSQL).
 *
 * <p>This is the fallback the cache misses to, and the target of the batch
 * flush. Read methods are tuned to ride the {@code (query_norm, count)}
 * indexes; the write method is an idempotent UPSERT used by the batch writer so
 * that aggregating N searches of the same query becomes a single statement.
 */
@Repository
public interface SearchQueryRepository extends JpaRepository<SearchQuery, String> {

    /**
     * Prefix lookup ordered by popularity. {@code query_norm LIKE :prefix%}
     * uses the B-tree index; {@code ORDER BY count DESC LIMIT :n} returns the
     * top suggestions. The caller passes {@code prefix + '%'} as {@code pattern}.
     */
    @Query("SELECT s FROM SearchQuery s WHERE s.queryNorm LIKE :pattern ESCAPE '\\' ORDER BY s.count DESC")
    List<SearchQuery> findTopByPrefix(@Param("pattern") String pattern, Pageable pageable);

    /** Trending version 1: the globally most-searched queries (all-time count). */
    @Query("SELECT s FROM SearchQuery s ORDER BY s.count DESC")
    List<SearchQuery> findTopByCount(Pageable pageable);

    /**
     * Popularity lookup by NORMALIZED text, used by trending v2 (the recency ZSET
     * stores normalized members while the PK is the display text). Returns the
     * highest-count row when several display variants share one normalized form.
     */
    Optional<SearchQuery> findFirstByQueryNormOrderByCountDesc(String queryNorm);

    /**
     * Atomic, aggregation-friendly UPSERT used by the batch flush.
     *
     * <p>If the query exists, its count is incremented by {@code delta} (the
     * aggregated number of searches in this batch) and {@code last_searched_at}
     * is advanced. Otherwise the row is inserted with {@code delta} as the
     * initial count. Native query so we can use PostgreSQL's
     * {@code ON CONFLICT ... DO UPDATE}.
     */
    @Modifying
    @Query(value = """
            INSERT INTO search_query (query, query_norm, count, last_searched_at, created_at)
            VALUES (:query, :queryNorm, :delta, :ts, :ts)
            ON CONFLICT (query) DO UPDATE
              SET count = search_query.count + EXCLUDED.count,
                  last_searched_at = EXCLUDED.last_searched_at
            """, nativeQuery = true)
    void upsertIncrement(@Param("query") String query,
                         @Param("queryNorm") String queryNorm,
                         @Param("delta") long delta,
                         @Param("ts") Instant ts);
}
