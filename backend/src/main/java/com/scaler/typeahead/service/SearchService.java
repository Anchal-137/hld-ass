package com.scaler.typeahead.service;

import com.scaler.typeahead.batch.SearchEventProducer;
import com.scaler.typeahead.dto.SearchResponse;
import com.scaler.typeahead.util.PrefixUtil;
import org.springframework.stereotype.Service;

/**
 * Handles {@code POST /search}. It does NOT touch the database directly:
 * <ol>
 *   <li>normalize the query,</li>
 *   <li>append a count-update event to the Redis Stream (batch writes),</li>
 *   <li>bump the recency signal for trending,</li>
 *   <li>return the dummy {@code "Searched"} response immediately.</li>
 * </ol>
 * The actual count increment is applied later by the batch consumer.
 */
@Service
public class SearchService {

    private final SearchEventProducer producer;
    private final TrendingService trendingService;

    public SearchService(SearchEventProducer producer, TrendingService trendingService) {
        this.producer = producer;
        this.trendingService = trendingService;
    }

    public SearchResponse submit(String rawQuery) {
        String display = rawQuery.trim();
        String norm = PrefixUtil.normalize(rawQuery);

        // 1) Durable enqueue (batch write path).
        producer.publish(norm, display);

        // 2) Recency signal for trending (cheap Redis ZINCRBY).
        trendingService.recordSearch(norm);

        // 3) Dummy response - the search "ran".
        return SearchResponse.searched(display);
    }
}
