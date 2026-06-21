package com.scaler.typeahead.batch;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Time-based flush. The consumer flushes when the buffer hits the size
 * threshold; this scheduler guarantees a flush at least every
 * {@code batch.flush-interval-ms} even under light traffic, so a few searches
 * are not stranded in the buffer indefinitely.
 *
 * <p>Together the two triggers implement the assignment's requirement to "flush
 * periodically OR after reaching a configurable batch threshold".
 */
@Component
public class BatchFlushScheduler {

    private final BatchAggregator aggregator;

    public BatchFlushScheduler(BatchAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Scheduled(fixedDelayString = "${batch.flush-interval-ms:5000}")
    public void scheduledFlush() {
        aggregator.flush();
    }
}
