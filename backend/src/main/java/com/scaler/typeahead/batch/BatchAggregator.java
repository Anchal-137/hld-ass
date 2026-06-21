package com.scaler.typeahead.batch;

import com.scaler.typeahead.trie.TrieService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory aggregation buffer that sits between the stream consumer and the DB.
 *
 * <p>The consumer hands every event to {@link #add}. Repeated queries are merged
 * (count += 1) so a window of 1,000 searches for "iphone" collapses to a single
 * {@code +1000} update. {@link #flush()} drains the buffer, persists the
 * aggregated updates in one transaction, then {@code XACK}s exactly the stream
 * records that were folded into this flush.
 *
 * <p><b>Ordering of flush steps matters for durability:</b> we persist to
 * Postgres FIRST and only {@code XACK} AFTER the commit succeeds. If the process
 * dies between persist and ack, those records stay in the consumer group's
 * Pending Entries List and are re-processed on restart - at-least-once delivery.
 * The DB upsert is additive, so a re-applied duplicate batch would over-count;
 * we therefore ack immediately after a successful commit to keep the duplicate
 * window tiny (see DESIGN.md "exactly-once vs at-least-once" trade-off).
 */
@Component
public class BatchAggregator {

    private static final Logger log = LoggerFactory.getLogger(BatchAggregator.class);

    private final BatchPersister persister;
    private final TrieService trieService;
    private final StringRedisTemplate redis;
    private final String streamKey;
    private final String group;
    private final int flushThreshold;

    private final ReentrantLock lock = new ReentrantLock();
    // queryNorm -> aggregated state
    private Map<String, Acc> buffer = new HashMap<>();
    private List<RecordId> pendingIds = new ArrayList<>();

    // ---- metrics for the write-reduction report --------------------------
    private final LongAdder eventsReceived = new LongAdder();
    private final LongAdder dbRowsWritten = new LongAdder();
    private final LongAdder flushes = new LongAdder();

    public BatchAggregator(BatchPersister persister,
                           TrieService trieService,
                           StringRedisTemplate streamsRedisTemplate,
                           @Value("${batch.stream-key:search-events}") String streamKey,
                           @Value("${batch.consumer-group:batch-writers}") String group,
                           @Value("${batch.flush-threshold:500}") int flushThreshold) {
        this.persister = persister;
        this.trieService = trieService;
        this.redis = streamsRedisTemplate;
        this.streamKey = streamKey;
        this.group = group;
        this.flushThreshold = flushThreshold;
    }

    /**
     * Buffer one event. Returns true if the buffer reached the flush threshold
     * (the consumer then calls {@link #flush()} promptly).
     */
    public boolean add(RecordId id, String queryNorm, String display) {
        lock.lock();
        try {
            buffer.computeIfAbsent(queryNorm, k -> new Acc(display)).delta++;
            pendingIds.add(id);
            eventsReceived.increment();
            return pendingIds.size() >= flushThreshold;
        } finally {
            lock.unlock();
        }
    }

    /** Drain + persist + acknowledge. Safe to call from consumer or scheduler. */
    public void flush() {
        Map<String, Acc> toWrite;
        List<RecordId> toAck;

        // 1) Atomically snapshot and reset the buffer so new events keep flowing.
        lock.lock();
        try {
            if (pendingIds.isEmpty()) {
                return;
            }
            toWrite = buffer;
            toAck = pendingIds;
            buffer = new HashMap<>();
            pendingIds = new ArrayList<>();
        } finally {
            lock.unlock();
        }

        // 2) Persist OUTSIDE the lock (one transaction for the whole batch).
        List<AggregatedWrite> writes = new ArrayList<>(toWrite.size());
        toWrite.forEach((norm, acc) -> writes.add(new AggregatedWrite(norm, acc.display, acc.delta)));

        try {
            persister.persist(writes);
        } catch (RuntimeException e) {
            // Persist failed -> do NOT ack. Records remain pending and will be
            // retried. Re-buffer the snapshot so the timed flush retries too.
            requeue(toWrite, toAck);
            log.error("Batch flush failed ({} writes); will retry. cause={}", writes.size(), e.toString());
            return;
        }

        // 3) Commit succeeded -> acknowledge the stream records.
        redis.opsForStream().acknowledge(streamKey, group, toAck.toArray(new RecordId[0]));

        // 4) Keep the in-memory Trie in sync (no-op when Trie mode is disabled).
        if (trieService.isEnabled()) {
            writes.forEach(w -> trieService.upsert(w.queryNorm(), w.delta()));
        }

        dbRowsWritten.add(writes.size());
        flushes.increment();
        log.debug("Flushed batch: {} events -> {} DB upserts", toAck.size(), writes.size());
    }

    private void requeue(Map<String, Acc> failedWrites, List<RecordId> failedIds) {
        lock.lock();
        try {
            failedWrites.forEach((norm, acc) ->
                    buffer.merge(norm, acc, (a, b) -> {
                        a.delta += b.delta;
                        return a;
                    }));
            pendingIds.addAll(failedIds);
        } finally {
            lock.unlock();
        }
    }

    public long eventsReceived() {
        return eventsReceived.sum();
    }

    public long dbRowsWritten() {
        return dbRowsWritten.sum();
    }

    public long flushes() {
        return flushes.sum();
    }

    /** Aggregated counter for a single query within the current window. */
    private static final class Acc {
        final String display;
        long delta;

        Acc(String display) {
            this.display = display;
        }
    }
}
