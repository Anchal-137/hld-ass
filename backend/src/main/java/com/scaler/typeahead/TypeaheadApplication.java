package com.scaler.typeahead;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Search Typeahead System.
 *
 * <p>High-level responsibilities wired up by this application:
 * <ul>
 *   <li>Serve prefix suggestions via {@code GET /suggest} backed by a
 *       consistent-hashing distributed Redis cache in front of PostgreSQL.</li>
 *   <li>Accept searches via {@code POST /search}, returning a dummy response and
 *       enqueuing a count-update event onto a Redis Stream (batch writes).</li>
 *   <li>Compute trending searches using a recency + frequency decayed score.</li>
 *   <li>Expose {@code GET /cache/debug} to show consistent-hashing routing.</li>
 * </ul>
 *
 * {@link EnableScheduling} powers the periodic batch flush and the periodic
 * trending recompute.
 */
@SpringBootApplication
@EnableScheduling
public class TypeaheadApplication {

    public static void main(String[] args) {
        SpringApplication.run(TypeaheadApplication.class, args);
    }
}
