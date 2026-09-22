package com.demo.upimesh.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small in-memory ring buffer of what the backend just did (inject, gossip,
 * flush, settle, duplicate-drop, tamper-reject...). Feeds the dashboard's
 * activity log so it shows real backend events rather than client-side guesses.
 */
@Service
public class EventLogService {

    private static final int MAX_EVENTS = 200;

    private final Deque<Event> events = new ConcurrentLinkedDeque<>();
    private final AtomicLong   seq    = new AtomicLong();

    public void record(String type, String message) {
        events.addFirst(new Event(seq.incrementAndGet(), Instant.now().toString(), type, message));
        while (events.size() > MAX_EVENTS) {
            events.pollLast();
        }
    }

    /** Newest first. */
    public List<Event> recent(int limit) {
        List<Event> out = new ArrayList<>();
        Iterator<Event> it = events.iterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        return out;
    }

    public void clear() {
        events.clear();
    }

    public record Event(long id, String at, String type, String message) {}
}
