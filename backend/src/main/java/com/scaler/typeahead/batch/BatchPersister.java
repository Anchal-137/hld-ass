package com.scaler.typeahead.batch;

import com.scaler.typeahead.repository.SearchQueryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;

/**
 * Persists a batch of aggregated count updates in a SINGLE database transaction.
 *
 * <p>Kept as its own bean (not a private method on the aggregator) so the
 * {@link Transactional} proxy actually applies - a self-invoked {@code @Transactional}
 * method would be bypassed. One transaction for the whole batch is what turns N
 * per-search commits into 1 commit, the core write-reduction win.
 */
@Component
public class BatchPersister {

    private final SearchQueryRepository repository;

    public BatchPersister(SearchQueryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void persist(Collection<AggregatedWrite> writes) {
        Instant now = Instant.now();
        for (AggregatedWrite w : writes) {
            repository.upsertIncrement(w.display(), w.queryNorm(), w.delta(), now);
        }
    }
}
