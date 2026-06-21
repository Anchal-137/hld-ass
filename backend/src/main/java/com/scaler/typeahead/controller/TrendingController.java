package com.scaler.typeahead.controller;

import com.scaler.typeahead.dto.TrendingResponse;
import com.scaler.typeahead.service.TrendingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code GET /trending?mode=recency|popularity} - trending searches.
 *
 * <p>The result is cached in-process with a short TTL equal to the recompute
 * interval, so the ranking refreshes automatically (cache invalidation by
 * expiry) without hammering Redis/DB on every poll from the UI.
 */
@RestController
public class TrendingController {

    private final TrendingService trendingService;
    private final long ttlMs;

    // tiny single-entry caches per mode: (value, expiryEpochMs)
    private final AtomicReference<Cached> popularityCache = new AtomicReference<>();
    private final AtomicReference<Cached> recencyCache = new AtomicReference<>();

    public TrendingController(TrendingService trendingService,
                              @Value("${trending.recompute-interval-ms:15000}") long ttlMs) {
        this.trendingService = trendingService;
        this.ttlMs = ttlMs;
    }

    @GetMapping("/trending")
    public TrendingResponse trending(@RequestParam(name = "mode", defaultValue = "recency") String mode) {
        boolean recency = !"popularity".equalsIgnoreCase(mode);
        AtomicReference<Cached> slot = recency ? recencyCache : popularityCache;

        Cached c = slot.get();
        long now = System.currentTimeMillis();
        if (c != null && now < c.expiry) {
            // serve cached copy but mark source as CACHE
            TrendingResponse r = c.value;
            return new TrendingResponse(r.mode(), r.suggestions(), "CACHE", 0);
        }

        TrendingResponse fresh = recency
                ? trendingService.trendingByRecency()
                : trendingService.trendingByPopularity();
        slot.set(new Cached(fresh, now + ttlMs));
        return fresh;
    }

    private record Cached(TrendingResponse value, long expiry) {
    }
}
