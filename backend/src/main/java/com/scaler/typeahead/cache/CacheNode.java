package com.scaler.typeahead.cache;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * One logical member of the distributed cache ring.
 *
 * <p>Each {@code CacheNode} wraps its own {@link StringRedisTemplate} bound to a
 * single Redis endpoint (host:port:db). The consistent-hashing router owns a
 * list of these and selects exactly one per prefix key. Because every node has
 * an independent connection, this models a real sharded cache: a key lives on
 * one node only, and adding/removing a node only remaps keys near it on the ring.
 */
public class CacheNode {

    private final String id;            // e.g. "node-0 (localhost:6379/0)"
    private final StringRedisTemplate redis;

    public CacheNode(String id, StringRedisTemplate redis) {
        this.id = id;
        this.redis = redis;
    }

    public String id() {
        return id;
    }

    public StringRedisTemplate redis() {
        return redis;
    }

    /** Approximate live key count on this node (used by the debug endpoint). */
    public long size() {
        Long n = redis.execute((org.springframework.data.redis.connection.RedisConnection c)
                -> c.serverCommands().dbSize());
        return n == null ? 0L : n;
    }
}
