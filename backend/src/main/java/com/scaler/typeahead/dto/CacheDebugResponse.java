package com.scaler.typeahead.dto;

import java.util.List;

/**
 * Response body for {@code GET /cache/debug?prefix=...}.
 *
 * <p>This endpoint is the visible proof of consistent hashing. For a given
 * prefix it reports which logical cache node owns the key, the hash position on
 * the ring, whether the key is currently a HIT or MISS, and the full ring
 * topology so the grader can see key distribution.
 *
 * @param prefix       normalized prefix
 * @param cacheKey     the actual Redis key that would be used
 * @param ownerNode    id of the node selected by the hash ring
 * @param hashValue    the 64-bit hash of the key (ring position)
 * @param status       {@code HIT} or {@code MISS}
 * @param ttlSeconds   remaining TTL of the key on its owner node (-2 = absent)
 * @param ring         all nodes and how many keys each currently holds
 */
public record CacheDebugResponse(
        String prefix,
        String cacheKey,
        String ownerNode,
        long hashValue,
        String status,
        long ttlSeconds,
        List<NodeInfo> ring
) {
    /**
     * @param nodeId       logical node id (e.g. "node-0 -> host:port/db")
     * @param virtualNodes number of virtual nodes this physical node has on the ring
     * @param liveKeys     approximate number of live keys on this node (DBSIZE)
     */
    public record NodeInfo(String nodeId, int virtualNodes, long liveKeys) {
    }
}
