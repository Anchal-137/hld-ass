package com.scaler.typeahead.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaler.typeahead.dto.SuggestionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * The cache layer the suggestion flow talks to. It hides the ring: callers pass
 * a full cache key, and this service routes it to the owning node, reads/writes
 * JSON, and records hit/miss metrics for the performance report.
 *
 * <p>The unit of caching is the whole top-N list for a (prefix, ranking-mode),
 * stored as a JSON array of {@link SuggestionDto} under the caller-supplied key
 * (e.g. {@code suggest:<prefix>} for popularity, {@code suggest:recency:<prefix>}
 * for recency). One GET returns everything, one SET replaces everything, TTL
 * expiry retires the unit atomically.
 */
@Service
public class DistributedCacheService {

    private static final Logger log = LoggerFactory.getLogger(DistributedCacheService.class);

    private final ConsistentHashRouter router;
    private final ObjectMapper mapper;

    // ---- metrics for the performance report -------------------------------
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public DistributedCacheService(ConsistentHashRouter router, ObjectMapper mapper) {
        this.router = router;
        this.mapper = mapper;
    }

    /** Look up a cached suggestion list by its full cache key. */
    public Optional<List<SuggestionDto>> get(String cacheKey) {
        CacheNode node = router.route(cacheKey);
        try {
            String json = node.redis().opsForValue().get(cacheKey);
            if (json == null) {
                misses.increment();
                return Optional.empty();
            }
            hits.increment();
            return Optional.of(mapper.readValue(json, new TypeReference<List<SuggestionDto>>() {
            }));
        } catch (Exception e) {
            // A cache failure must never break the request; degrade to a miss
            // and let the caller fall back to the primary store.
            misses.increment();
            log.warn("Cache GET failed for key={} on {}: {}", cacheKey, node.id(), e.toString());
            return Optional.empty();
        }
    }

    /** Cache a suggestion list under its key with the given TTL (seconds). */
    public void put(String cacheKey, List<SuggestionDto> suggestions, long ttlSeconds) {
        CacheNode node = router.route(cacheKey);
        try {
            String json = mapper.writeValueAsString(suggestions);
            node.redis().opsForValue().set(cacheKey, json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("Cache PUT failed for key={} on {}: {}", cacheKey, node.id(), e.toString());
        }
    }

    /** Explicitly invalidate a key (used when a write changes its ranking). */
    public void invalidate(String cacheKey) {
        CacheNode node = router.route(cacheKey);
        try {
            node.redis().delete(cacheKey);
        } catch (Exception e) {
            log.warn("Cache INVALIDATE failed for key={} on {}: {}", cacheKey, node.id(), e.toString());
        }
    }

    /** Id of the node that owns this key (for the debug endpoint / response). */
    public String ownerNodeId(String cacheKey) {
        return router.route(cacheKey).id();
    }

    public double hitRate() {
        long h = hits.sum();
        long total = h + misses.sum();
        return total == 0 ? 0.0 : (double) h / total;
    }

    public long hitCount() {
        return hits.sum();
    }

    public long missCount() {
        return misses.sum();
    }
}
