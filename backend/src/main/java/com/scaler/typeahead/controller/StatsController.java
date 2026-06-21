package com.scaler.typeahead.controller;

import com.scaler.typeahead.batch.BatchAggregator;
import com.scaler.typeahead.cache.DistributedCacheService;
import com.scaler.typeahead.service.SuggestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /stats} - live numbers for the performance report (§10):
 * cache hit rate, database read/write counts, and batch write-reduction.
 */
@RestController
public class StatsController {

    private final DistributedCacheService cache;
    private final BatchAggregator aggregator;
    private final SuggestionService suggestionService;

    public StatsController(DistributedCacheService cache,
                           BatchAggregator aggregator,
                           SuggestionService suggestionService) {
        this.cache = cache;
        this.aggregator = aggregator;
        this.suggestionService = suggestionService;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();

        Map<String, Object> cacheStats = new LinkedHashMap<>();
        cacheStats.put("hits", cache.hitCount());
        cacheStats.put("misses", cache.missCount());
        cacheStats.put("hitRate", round(cache.hitRate()));
        m.put("cache", cacheStats);

        // Explicit database read/write counts (§10 "database read/write counts").
        Map<String, Object> dbStats = new LinkedHashMap<>();
        dbStats.put("reads", suggestionService.dbReadCount());   // Postgres prefix reads on cache miss
        dbStats.put("writes", aggregator.dbRowsWritten());       // batched upsert rows
        m.put("db", dbStats);

        Map<String, Object> batchStats = new LinkedHashMap<>();
        long events = aggregator.eventsReceived();
        long rows = aggregator.dbRowsWritten();
        batchStats.put("eventsReceived", events);
        batchStats.put("dbRowsWritten", rows);
        batchStats.put("flushes", aggregator.flushes());
        batchStats.put("writeReductionRatio", rows == 0 ? 0.0 : round((double) events / rows));
        batchStats.put("writeReductionPercent", events == 0 ? 0.0 : round(100.0 * (1.0 - (double) rows / events)));
        m.put("batch", batchStats);

        return m;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
