/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RateLimiter {

    private final int maxPerWindow;
    private final Duration window;
    private final ConcurrentMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public RateLimiter(int maxPerWindow, Duration window) {
        this.maxPerWindow = maxPerWindow;
        this.window = window;
    }

    public boolean tryAcquire(String key) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        Deque<Instant> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
                deque.pollFirst();
            }
            if (deque.size() >= maxPerWindow) {
                return false;
            }
            deque.offerLast(now);
            return true;
        }
    }
}
