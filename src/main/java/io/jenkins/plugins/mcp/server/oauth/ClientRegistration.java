/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.oauth;

import java.io.Serializable;
import java.util.List;

public final class ClientRegistration implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String clientId;
    private final String clientName;
    private final List<String> redirectUris;
    private final long createdAtEpoch;
    private volatile long lastUsedEpoch;

    public ClientRegistration(String clientId, String clientName, List<String> redirectUris, long createdAtEpoch) {
        this.clientId = clientId;
        this.clientName = clientName;
        this.redirectUris = List.copyOf(redirectUris);
        this.createdAtEpoch = createdAtEpoch;
        this.lastUsedEpoch = createdAtEpoch;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientName() {
        return clientName;
    }

    public List<String> getRedirectUris() {
        return redirectUris;
    }

    public long getCreatedAtEpoch() {
        return createdAtEpoch;
    }

    public long getLastUsedEpoch() {
        return lastUsedEpoch;
    }

    public void touch(long nowEpoch) {
        this.lastUsedEpoch = nowEpoch;
    }

    public boolean allowsRedirectUri(String redirectUri) {
        return redirectUris.contains(redirectUri);
    }
}
