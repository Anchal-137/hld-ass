package com.scaler.typeahead.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Classic consistent-hashing ring with virtual nodes.
 *
 * <p><b>Why consistent hashing (viva):</b> with naive modulo sharding
 * ({@code hash(key) % N}), changing the node count N remaps almost every key,
 * collapsing the cache. Consistent hashing maps both nodes and keys onto the
 * same 64-bit ring; a key is owned by the first node clockwise from it. Adding
 * or removing a node only moves the keys in one arc (~1/N of keys), so the
 * cache stays mostly warm during scaling.
 *
 * <p><b>Virtual nodes:</b> hashing each physical node once produces lumpy arcs
 * and unbalanced load. We place each physical node at {@code virtualNodes}
 * positions (replicas) on the ring, which smooths key distribution toward
 * uniform. 150 replicas/node keeps the standard deviation of load low.
 *
 * <p>Hash function is MD5 truncated to 64 bits - chosen for good distribution
 * and determinism across JVMs (so the ring is reproducible in the debug
 * endpoint). It is NOT used for security, so MD5's cryptographic weakness is
 * irrelevant here.
 */
public class ConsistentHashRouter {

    /** ring position -> physical node index */
    private final SortedMap<Long, Integer> ring = new TreeMap<>();
    private final List<CacheNode> nodes;
    private final int virtualNodes;

    public ConsistentHashRouter(List<CacheNode> nodes, int virtualNodes) {
        this.nodes = new ArrayList<>(nodes);
        this.virtualNodes = virtualNodes;
        for (int i = 0; i < this.nodes.size(); i++) {
            addToRing(i);
        }
    }

    private void addToRing(int nodeIndex) {
        String nodeId = nodes.get(nodeIndex).id();
        for (int v = 0; v < virtualNodes; v++) {
            long h = hash(nodeId + "#vn" + v);
            ring.put(h, nodeIndex);
        }
    }

    /** Returns the node that owns the given key (first node clockwise). */
    public CacheNode route(String key) {
        return nodes.get(routeIndex(key));
    }

    /** Owner node index for a key - exposed for the debug endpoint. */
    public int routeIndex(String key) {
        if (ring.isEmpty()) {
            throw new IllegalStateException("Consistent hash ring is empty");
        }
        long h = hash(key);
        // tailMap = all ring positions >= h. The first one is the owner; if h is
        // past the last position, wrap around to the first node on the ring.
        SortedMap<Long, Integer> tail = ring.tailMap(h);
        Long pos = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
        return ring.get(pos);
    }

    /** 64-bit ring position of a key - exposed for the debug endpoint. */
    public long positionOf(String key) {
        return hash(key);
    }

    public List<CacheNode> nodes() {
        return nodes;
    }

    public int virtualNodes() {
        return virtualNodes;
    }

    /** MD5 -> first 8 bytes folded into a long. Deterministic & well-distributed. */
    static long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(key.getBytes(StandardCharsets.UTF_8));
            long h = 0L;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (d[i] & 0xffL);
            }
            return h;
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed present on every JVM; this never happens.
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
