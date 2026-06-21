package com.scaler.typeahead.batch;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * CONSUMER side of the batch pipeline. Runs a single background thread that
 * reads the {@code search-events} stream through a consumer group and feeds the
 * {@link BatchAggregator}.
 *
 * <p><b>Crash recovery:</b> on startup we first replay this consumer's Pending
 * Entries List ({@code ReadOffset.from("0")}) - any events that were delivered
 * but not acknowledged before a previous crash - and only then switch to new
 * messages ({@code ReadOffset.lastConsumed()}, i.e. "{@code >}"). This is what
 * makes the pipeline at-least-once and survives an app crash with un-flushed
 * events still buffered.
 */
@Component
public class SearchEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SearchEventConsumer.class);

    private final StringRedisTemplate redis;
    private final BatchAggregator aggregator;
    private final String streamKey;
    private final String group;
    private final String consumerName;
    private final int pollCount;
    private final long pollBlockMs;

    private volatile boolean running = true;
    private Thread worker;

    public SearchEventConsumer(@Qualifier("consumerRedisTemplate") StringRedisTemplate consumerRedisTemplate,
                               BatchAggregator aggregator,
                               @Value("${batch.stream-key:search-events}") String streamKey,
                               @Value("${batch.consumer-group:batch-writers}") String group,
                               @Value("${batch.consumer-name:consumer-1}") String consumerName,
                               @Value("${batch.poll-count:256}") int pollCount,
                               @Value("${batch.poll-block-ms:2000}") long pollBlockMs) {
        this.redis = consumerRedisTemplate;
        this.aggregator = aggregator;
        this.streamKey = streamKey;
        this.group = group;
        this.consumerName = consumerName;
        this.pollCount = pollCount;
        this.pollBlockMs = pollBlockMs;
    }

    @PostConstruct
    void start() {
        ensureGroup();
        worker = new Thread(this::runLoop, "search-event-consumer");
        worker.setDaemon(true);
        worker.start();
        log.info("Search event consumer started (group={}, consumer={})", group, consumerName);
    }

    /** Create the stream + consumer group if they do not already exist. */
    private void ensureGroup() {
        try {
            // MKSTREAM-style create: starts the group at the beginning of the stream.
            redis.opsForStream().createGroup(streamKey, ReadOffset.from("0"), group);
            log.info("Created consumer group '{}' on stream '{}'", group, streamKey);
        } catch (DataAccessException e) {
            // BUSYGROUP: group already exists -> fine.
            log.info("Consumer group '{}' already exists (or stream present): {}", group, e.getMostSpecificCause().getMessage());
        }
    }

    private void runLoop() {
        // Phase 1: drain anything left pending for THIS consumer from a prior run.
        drainPending();

        // Phase 2: continuously consume new messages.
        Consumer consumer = Consumer.from(group, consumerName);
        StreamReadOptions opts = StreamReadOptions.empty().count(pollCount).block(Duration.ofMillis(pollBlockMs));
        StreamOffset<String> newMessages = StreamOffset.create(streamKey, ReadOffset.lastConsumed());

        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(consumer, opts, newMessages);
                if (records == null || records.isEmpty()) {
                    continue; // BLOCK timed out with nothing new
                }
                boolean thresholdHit = false;
                for (MapRecord<String, Object, Object> rec : records) {
                    thresholdHit |= handle(rec);
                }
                if (thresholdHit) {
                    aggregator.flush();
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("Consumer loop error, backing off: {}", e.toString());
                    sleep(500);
                }
            }
        }
    }

    private void drainPending() {
        Consumer consumer = Consumer.from(group, consumerName);
        StreamReadOptions opts = StreamReadOptions.empty().count(pollCount);
        StreamOffset<String> pending = StreamOffset.create(streamKey, ReadOffset.from("0"));
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(consumer, opts, pending);
        if (records != null && !records.isEmpty()) {
            log.info("Recovering {} pending events from previous run", records.size());
            for (MapRecord<String, Object, Object> rec : records) {
                handle(rec);
            }
            aggregator.flush();
        }
    }

    private boolean handle(MapRecord<String, Object, Object> rec) {
        Object q = rec.getValue().get(SearchEventProducer.FIELD_QUERY);
        Object d = rec.getValue().get(SearchEventProducer.FIELD_DISPLAY);
        if (q == null) {
            // Malformed event: ack it so it does not block the group forever.
            redis.opsForStream().acknowledge(streamKey, group, rec.getId());
            return false;
        }
        String display = d != null ? d.toString() : q.toString();
        return aggregator.add(rec.getId(), q.toString(), display);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        // Best-effort final flush so a graceful shutdown does not strand events.
        try {
            aggregator.flush();
        } catch (Exception e) {
            log.warn("Final flush on shutdown failed: {}", e.toString());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
