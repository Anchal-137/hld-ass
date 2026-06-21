package com.scaler.typeahead.util;

/**
 * Small helpers for normalizing query text and building cache keys.
 *
 * <p>Normalization rule (single source of truth, used by ingestion, suggest,
 * search and caching alike): {@code trim()} then {@code toLowerCase(ROOT)}.
 * Using {@link java.util.Locale#ROOT} avoids the Turkish-i class of locale bugs
 * where {@code "I".toLowerCase()} is not {@code "i"}.
 */
public final class PrefixUtil {

    private PrefixUtil() {
    }

    /** Normalize a raw query/prefix for matching and key building. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Cache key for a prefix's POPULARITY-ranked suggestion list. */
    public static String suggestKey(String normalizedPrefix) {
        return "suggest:" + normalizedPrefix;
    }

    /**
     * Cache key for a prefix's RECENCY-ranked suggestion list. Kept in a separate
     * keyspace so the two rankings never collide on the ring and each can carry
     * its own TTL (recency expires faster to stay fresh).
     */
    public static String recencySuggestKey(String normalizedPrefix) {
        return "suggest:recency:" + normalizedPrefix;
    }

    /** Escapes LIKE wildcards so a user-typed '%' or '_' is treated literally. */
    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
