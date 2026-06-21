package com.scaler.typeahead.service;

import com.scaler.typeahead.cache.DistributedCacheService;
import com.scaler.typeahead.dto.SuggestResponse;
import com.scaler.typeahead.dto.SuggestionDto;
import com.scaler.typeahead.entity.SearchQuery;
import com.scaler.typeahead.repository.SearchQueryRepository;
import com.scaler.typeahead.trie.TrieService;
import com.scaler.typeahead.util.PrefixUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Serves prefix suggestions following the assignment's required flow:
 * <pre>
 *   normalize prefix -> CACHE (consistent-hash routed) -> on miss: PRIMARY store
 *   -> populate cache -> return
 * </pre>
 *
 * <p>The SAME endpoint supports two ranking modes (assignment §7):
 * <ul>
 *   <li><b>POPULARITY</b> (basic) - prefix matches ordered by all-time count.</li>
 *   <li><b>RECENCY</b> (enhanced) - prefix matches re-ranked by a blended
 *       recency+frequency score via {@link TrendingService#rankByRecency}.</li>
 * </ul>
 * Each mode is cached under its own consistent-hashing key with its own TTL
 * (recency expires faster to stay fresh). The primary store for the basic mode
 * is either the PostgreSQL prefix index ({@code suggest.source=DB}) or the
 * in-memory Trie ({@code suggest.source=TRIE}); recency always re-ranks a DB
 * candidate pool. Empty / no-match input degrades gracefully.
 */
@Service
public class SuggestionService {

    private final DistributedCacheService cache;
    private final SearchQueryRepository repository;
    private final TrieService trieService;
    private final TrendingService trendingService;
    private final int maxResults;
    private final boolean useTrie;
    private final long popularityTtl;
    private final long recencyTtl;
    private final int candidatePool;

    // Counts actual primary-store (Postgres) reads on the suggest path, for the
    // performance report's "database read counts" metric (§10).
    private final java.util.concurrent.atomic.LongAdder dbReads = new java.util.concurrent.atomic.LongAdder();

    public SuggestionService(DistributedCacheService cache,
                             SearchQueryRepository repository,
                             TrieService trieService,
                             TrendingService trendingService,
                             @Value("${suggest.max-results:10}") int maxResults,
                             @Value("${suggest.source:DB}") String source,
                             @Value("${cache.suggest-ttl-seconds:60}") long popularityTtl,
                             @Value("${cache.recency-suggest-ttl-seconds:15}") long recencyTtl) {
        this.cache = cache;
        this.repository = repository;
        this.trieService = trieService;
        this.trendingService = trendingService;
        this.maxResults = maxResults;
        this.useTrie = "TRIE".equalsIgnoreCase(source);
        this.popularityTtl = popularityTtl;
        this.recencyTtl = recencyTtl;
        // For recency we re-rank a larger pool so recent-but-less-popular queries
        // can surface above the all-time leaders.
        this.candidatePool = Math.max(maxResults * 5, 50);
    }

    public SuggestResponse suggest(String rawPrefix, String mode) {
        long start = System.nanoTime();
        boolean recency = "recency".equalsIgnoreCase(mode);
        String modeLabel = recency ? "RECENCY" : "POPULARITY";
        String prefix = PrefixUtil.normalize(rawPrefix);

        // Graceful handling of empty / missing input: no prefix -> no suggestions.
        if (prefix.isEmpty()) {
            return new SuggestResponse("", modeLabel, List.of(), "EMPTY", "-", elapsedMs(start));
        }

        String cacheKey = recency ? PrefixUtil.recencySuggestKey(prefix) : PrefixUtil.suggestKey(prefix);
        long ttl = recency ? recencyTtl : popularityTtl;
        String ownerNode = cache.ownerNodeId(cacheKey);

        // 1) Cache lookup (routed to the owning node by consistent hashing).
        Optional<List<SuggestionDto>> cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            return new SuggestResponse(prefix, modeLabel, cached.get(), "CACHE", ownerNode, elapsedMs(start));
        }

        // 2) Cache miss -> primary store (+ recency re-rank when requested).
        List<SuggestionDto> suggestions;
        String source;
        if (recency) {
            List<SuggestionDto> candidates = fromDatabase(prefix, candidatePool);
            suggestions = trendingService.rankByRecency(candidates, maxResults);
            source = "DB+RECENCY";
        } else if (useTrie) {
            suggestions = fromTrie(prefix);
            source = "TRIE";
        } else {
            suggestions = fromDatabase(prefix, maxResults);
            source = "DB";
        }

        // 3) Populate cache for next time (TTL handles staleness).
        cache.put(cacheKey, suggestions, ttl);

        return new SuggestResponse(prefix, modeLabel, suggestions, source, ownerNode, elapsedMs(start));
    }

    private List<SuggestionDto> fromDatabase(String prefix, int limit) {
        dbReads.increment();
        String pattern = PrefixUtil.escapeLike(prefix) + "%";
        List<SearchQuery> rows = repository.findTopByPrefix(pattern, PageRequest.of(0, limit));
        return rows.stream()
                .map(r -> new SuggestionDto(r.getQuery(), r.getCount()))
                .toList();
    }

    /** Number of Postgres prefix reads served on cache misses (perf report). */
    public long dbReadCount() {
        return dbReads.sum();
    }

    private List<SuggestionDto> fromTrie(String prefix) {
        return trieService.suggest(prefix, maxResults);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
