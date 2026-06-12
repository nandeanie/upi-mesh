package com.demo.upimesh.service;

/**
 * Idempotency store abstraction.
 *
 * Default implementation: InMemoryIdempotencyStore (ConcurrentHashMap).
 * Production implementation: swap for RedisIdempotencyStore (SET NX EX).
 *
 * The contract is simple:
 *   claim(hash) → true  if this caller is FIRST (proceed with settlement)
 *   claim(hash) → false if someone else already claimed it (duplicate — drop)
 *
 * The claim must be ATOMIC. Two threads calling claim(hash) at the exact
 * same nanosecond must produce exactly one true and one false.
 */
public interface IdempotencyStore {
    boolean claim(String packetHash);
    void    clear();
    int     size();
}
