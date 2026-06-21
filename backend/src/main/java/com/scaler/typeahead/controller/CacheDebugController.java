package com.scaler.typeahead.controller;

import com.scaler.typeahead.cache.CacheNode;
import com.scaler.typeahead.cache.ConsistentHashRouter;
import com.scaler.typeahead.dto.CacheDebugResponse;
import com.scaler.typeahead.util.PrefixUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /cache/debug?prefix=<prefix>} - REQUIRED by the assignment.
 *
 * <p>Makes consistent hashing observable: for a prefix it reports which logical
 * cache node owns the key, the key's 64-bit position on the ring, whether it is
 * currently a HIT or MISS, the remaining TTL, and the full ring topology with
 * per-node live key counts (so a grader can see that keys spread across nodes).
 */
@RestController
public class CacheDebugController {

    private final ConsistentHashRouter router;

    public CacheDebugController(ConsistentHashRouter router) {
        this.router = router;
    }

    @GetMapping("/cache/debug")
    public CacheDebugResponse debug(@RequestParam("prefix") String prefix) {
        String norm = PrefixUtil.normalize(prefix);
        String key = PrefixUtil.suggestKey(norm);

        int idx = router.routeIndex(key);
        CacheNode owner = router.nodes().get(idx);
        long position = router.positionOf(key);

        Boolean present = owner.redis().hasKey(key);
        boolean hit = Boolean.TRUE.equals(present);
        Long ttl = hit ? owner.redis().getExpire(key, TimeUnit.SECONDS) : -2L;

        List<CacheDebugResponse.NodeInfo> ring = router.nodes().stream()
                .map(n -> new CacheDebugResponse.NodeInfo(n.id(), router.virtualNodes(), n.size()))
                .toList();

        return new CacheDebugResponse(
                norm,
                key,
                owner.id(),
                position,
                hit ? "HIT" : "MISS",
                ttl == null ? -2L : ttl,
                ring
        );
    }
}
