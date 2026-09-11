/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 */
package io.jenkins.plugins.mcp.server.auth;

import java.io.Serializable;

public final class IssuanceRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String tokenUuid;
    private final String tokenName;
    private final String userId;
    private final long issuedAtEpoch;
    private final long expiresAtEpoch;
    private final String label;
    private final String remoteAddr;

    public IssuanceRecord(
            String tokenUuid,
            String tokenName,
            String userId,
            long issuedAtEpoch,
            long expiresAtEpoch,
            String label,
            String remoteAddr) {
        this.tokenUuid = tokenUuid;
        this.tokenName = tokenName;
        this.userId = userId;
        this.issuedAtEpoch = issuedAtEpoch;
        this.expiresAtEpoch = expiresAtEpoch;
        this.label = label;
        this.remoteAddr = remoteAddr;
    }

    public String getTokenUuid() {
        return tokenUuid;
    }

    public String getTokenName() {
        return tokenName;
    }

    public String getUserId() {
        return userId;
    }

    public long getIssuedAtEpoch() {
        return issuedAtEpoch;
    }

    public long getExpiresAtEpoch() {
        return expiresAtEpoch;
    }

    public String getLabel() {
        return label;
    }

    public String getRemoteAddr() {
        return remoteAddr;
    }
}
