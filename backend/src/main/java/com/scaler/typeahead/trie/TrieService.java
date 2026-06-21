package com.scaler.typeahead.trie;

import com.scaler.typeahead.dto.SuggestionDto;
import com.scaler.typeahead.entity.SearchQuery;
import com.scaler.typeahead.repository.SearchQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the in-memory {@link Trie} and keeps it loosely in sync with the primary
 * store. The Trie is built from a full snapshot at startup (only when
 * {@code suggest.source=TRIE}) and rebuilt periodically. Reads go against an
 * immutable Trie referenced through an {@link AtomicReference}; a rebuild swaps
 * the reference atomically, so readers never see a half-built tree.
 */
@Service
public class TrieService {

    private static final Logger log = LoggerFactory.getLogger(TrieService.class);

    private final SearchQueryRepository repository;
    private final boolean enabled;
    private final AtomicReference<Trie> ref = new AtomicReference<>(new Trie());

    public TrieService(SearchQueryRepository repository,
                       @Value("${suggest.source:DB}") String source) {
        this.repository = repository;
        this.enabled = "TRIE".equalsIgnoreCase(source);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Full rebuild from the primary store. O(N * avgLen). */
    public void rebuild() {
        long start = System.currentTimeMillis();
        Trie next = new Trie();
        for (SearchQuery q : repository.findAll()) {
            next.insert(q.getQueryNorm(), q.getCount());
        }
        ref.set(next);
        log.info("Trie rebuilt: {} queries in {} ms", next.wordCount(), System.currentTimeMillis() - start);
    }

    public List<SuggestionDto> suggest(String prefixNorm, int limit) {
        return ref.get().search(prefixNorm, limit);
    }

    /** Apply an incremental count update (by delta) without a full rebuild. */
    public void upsert(String queryNorm, long delta) {
        ref.get().increment(queryNorm, delta);
    }
}
