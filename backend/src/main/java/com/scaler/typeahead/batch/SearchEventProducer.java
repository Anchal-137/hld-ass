package com.scaler.typeahead.batch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * PRODUCER side of the batch-write pipeline.
 *
 * <p>{@code POST /search} calls this instead of writing to PostgreSQL. It appends
 * one event to the Redis Stream {@code search-events} via {@code XADD}. The call
 * is O(1) and returns immediately, so the search request never waits on a DB
 * write - that is the whole point of batching.
 *
 * <p><b>Durability:</b> once {@code XADD} returns, the event is persisted in
 * Redis (and survives to disk per Redis' AOF/RDB policy). It is only removed from
 * the consumer group's pending list after the consumer has flushed it to
 * Postgres and {@code XACK}'d it - so an app crash cannot silently lose a search.
 */
@Component
public class SearchEventProducer {

    public static final String FIELD_QUERY = "q";
    public static final String FIELD_DISPLAY = "d";

    private final StringRedisTemplate redis;
    private final String streamKey;

    public SearchEventProducer(StringRedisTemplate streamsRedisTemplate,
                               @Value("${batch.stream-key:search-events}") String streamKey) {
        this.redis = streamsRedisTemplate;
        this.streamKey = streamKey;
    }

    /**
     * Append a search event to the stream.
     *
     * @param queryNorm normalized query (used for aggregation + matching)
     * @param display   original display text (stored so the row keeps user casing)
     */
    public void publish(String queryNorm, String display) {
        Map<String, String> body = Map.of(FIELD_QUERY, queryNorm, FIELD_DISPLAY, display);
        redis.opsForStream().add(StreamRecords.mapBacked(body).withStreamKey(streamKey));
    }
}
