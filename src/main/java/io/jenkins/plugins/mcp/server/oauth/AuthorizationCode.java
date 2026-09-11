/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.oauth;

public final class AuthorizationCode {

    private final String code;
    private final String clientId;
    private final String userId;
    private final String redirectUri;
    private final String codeChallenge;
    private final String scope;
    private final long expiresAtEpoch;

    public AuthorizationCode(
            String code,
            String clientId,
            String userId,
            String redirectUri,
            String codeChallenge,
            String scope,
            long expiresAtEpoch) {
        this.code = code;
        this.clientId = clientId;
        this.userId = userId;
        this.redirectUri = redirectUri;
        this.codeChallenge = codeChallenge;
        this.scope = scope;
        this.expiresAtEpoch = expiresAtEpoch;
    }

    public String getCode() {
        return code;
    }

    public String getClientId() {
        return clientId;
    }

    public String getUserId() {
        return userId;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public String getCodeChallenge() {
        return codeChallenge;
    }

    public String getScope() {
        return scope;
    }

    public long getExpiresAtEpoch() {
        return expiresAtEpoch;
    }
}
