package com.scaler.typeahead.service;

import com.scaler.typeahead.entity.SearchQuery;
import com.scaler.typeahead.repository.SearchQueryRepository;
import com.scaler.typeahead.trie.TrieService;
import com.scaler.typeahead.util.PrefixUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the search-query dataset (CSV: {@code query,count}) into PostgreSQL on
 * startup when the table is empty, then builds the Trie if Trie-mode is enabled.
 *
 * <p>Idempotent: if rows already exist it skips loading, so restarts are cheap.
 * Inserts are chunked with {@code saveAll} to keep memory flat for 100k+ rows.
 */
@Service
public class DatasetLoaderService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasetLoaderService.class);
    private static final int CHUNK = 2_000;

    private final SearchQueryRepository repository;
    private final TrieService trieService;
    private final ResourceLoader resourceLoader;
    private final boolean loadOnStartup;
    private final String csvPath;

    public DatasetLoaderService(SearchQueryRepository repository,
                                TrieService trieService,
                                ResourceLoader resourceLoader,
                                @Value("${dataset.load-on-startup:true}") boolean loadOnStartup,
                                @Value("${dataset.csv-path:classpath:data/sample_queries.csv}") String csvPath) {
        this.repository = repository;
        this.trieService = trieService;
        this.resourceLoader = resourceLoader;
        this.loadOnStartup = loadOnStartup;
        this.csvPath = csvPath;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (loadOnStartup) {
            long existing = repository.count();
            if (existing > 0) {
                log.info("Dataset load skipped: {} rows already present", existing);
            } else {
                load();
            }
        }
        if (trieService.isEnabled()) {
            trieService.rebuild();
        }
    }

    private void load() throws Exception {
        Resource resource = resourceLoader.getResource(csvPath);
        if (!resource.exists()) {
            log.warn("Dataset file '{}' not found; starting with an empty table", csvPath);
            return;
        }
        long start = System.currentTimeMillis();
        Instant now = Instant.now();
        int total = 0;
        List<SearchQuery> chunk = new ArrayList<>(CHUNK);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (first) {
                    first = false;
                    // Skip a header row like "query,count" if present.
                    if (line.toLowerCase().startsWith("query,")) {
                        continue;
                    }
                }
                SearchQuery row = parse(line, now);
                if (row == null) {
                    continue;
                }
                chunk.add(row);
                if (chunk.size() >= CHUNK) {
                    repository.saveAll(chunk);
                    total += chunk.size();
                    chunk.clear();
                }
            }
        }
        if (!chunk.isEmpty()) {
            repository.saveAll(chunk);
            total += chunk.size();
        }
        log.info("Loaded {} queries from {} in {} ms", total, csvPath, System.currentTimeMillis() - start);
    }

    /** Parse one CSV line "query,count" (split on the LAST comma to tolerate commas in queries). */
    private SearchQuery parse(String line, Instant now) {
        int idx = line.lastIndexOf(',');
        if (idx <= 0) {
            return null;
        }
        String query = line.substring(0, idx).trim();
        // Strip optional surrounding quotes from CSV-quoted queries.
        if (query.length() >= 2 && query.startsWith("\"") && query.endsWith("\"")) {
            query = query.substring(1, query.length() - 1).replace("\"\"", "\"");
        }
        String countStr = line.substring(idx + 1).trim();
        long count;
        try {
            count = Long.parseLong(countStr);
        } catch (NumberFormatException e) {
            return null;
        }
        if (query.isEmpty()) {
            return null;
        }
        return new SearchQuery(query, PrefixUtil.normalize(query), count, now, now);
    }
}
