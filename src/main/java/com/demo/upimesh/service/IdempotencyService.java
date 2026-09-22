package com.demo.upimesh.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency cache using ConcurrentHashMap.putIfAbsent.
 *
 * Semantics are identical to Redis SET NX EX — the distributed version
 * you'd use in production. To swap:
 *   1. Add spring-boot-starter-data-redis to pom.xml
 *   2. Implement IdempotencyStore using StringRedisTemplate.opsForValue().setIfAbsent()
 *   3. @Primary on the Redis impl; this class becomes the fallback
 *
 * Why ConcurrentHashMap.putIfAbsent?
 *   It's an atomic compare-and-set operation guaranteed by the JVM memory
 *   model. Even if 1000 threads call putIfAbsent(key, value) simultaneously,
 *   exactly one returns null (the first insertion). All others return the
 *   existing entry. This is what prevents the three-bridges-at-once problem.
 */
@Service
public class IdempotencyService implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    @Override
    public boolean claim(String packetHash) {
        Instant prev = seen.putIfAbsent(packetHash, Instant.now());
        boolean firstClaim = (prev == null);
        if (!firstClaim) {
            log.debug("Idempotency hit for hash {}...", packetHash.substring(0, 12));
        }
        return firstClaim;
    }

    @Override
    public int size() {
        return seen.size();
    }

    @Override
    public void clear() {
        seen.clear();
    }

    /** Evict entries past their TTL. Runs every minute. */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        int before = seen.size();
        seen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
        int evicted = before - seen.size();
        if (evicted > 0) {
            log.info("Idempotency eviction: removed {} expired entries", evicted);
        }
    }
}
