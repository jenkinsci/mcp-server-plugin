/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.oauth;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AuthorizationCodeStore {

    private static final AuthorizationCodeStore INSTANCE = new AuthorizationCodeStore();

    private final ConcurrentMap<String, AuthorizationCode> byCode = new ConcurrentHashMap<>();

    public static AuthorizationCodeStore get() {
        return INSTANCE;
    }

    public void put(AuthorizationCode code) {
        purge();
        byCode.put(code.getCode(), code);
    }

    public AuthorizationCode consume(String code) {
        purge();
        AuthorizationCode c = byCode.remove(code);
        if (c == null) return null;
        if (c.getExpiresAtEpoch() < Instant.now().getEpochSecond()) return null;
        return c;
    }

    private void purge() {
        long now = Instant.now().getEpochSecond();
        byCode.values().removeIf(c -> c.getExpiresAtEpoch() < now - 60);
    }
}
