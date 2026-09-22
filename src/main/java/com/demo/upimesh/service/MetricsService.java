package com.demo.upimesh.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for outcomes that are deliberately NOT written to the ledger:
 * duplicates dropped by the idempotency gate, and invalid (tampered / stale /
 * undecryptable) packets. Settled and rejected counts come from the database.
 */
@Service
public class MetricsService {

    private final AtomicLong duplicatesDropped = new AtomicLong();
    private final AtomicLong invalidPackets    = new AtomicLong();

    public void duplicateDropped()   { duplicatesDropped.incrementAndGet(); }
    public void invalidPacket()      { invalidPackets.incrementAndGet(); }

    public long getDuplicatesDropped() { return duplicatesDropped.get(); }
    public long getInvalidPackets()    { return invalidPackets.get(); }

    public void reset() {
        duplicatesDropped.set(0);
        invalidPackets.set(0);
    }
}
