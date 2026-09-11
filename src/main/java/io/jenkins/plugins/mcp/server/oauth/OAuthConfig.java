/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.oauth;

import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;

public final class OAuthConfig {

    public static final String ENABLED_PROP = "io.jenkins.plugins.mcp.server.oauth.enabled";
    public static final String DEFAULT_TTL_PROP = "io.jenkins.plugins.mcp.server.oauth.defaultTtlSeconds";

    private OAuthConfig() {}

    public static boolean isEnabled() {
        return SystemProperties.getBoolean(ENABLED_PROP, true);
    }

    public static long defaultTtlSeconds() {
        return SystemProperties.getLong(DEFAULT_TTL_PROP, 28_800L);
    }

    public static String issuer() {
        String root = Jenkins.get().getRootUrl();
        if (root == null || root.isEmpty()) {
            return "";
        }
        return root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
    }

    public static String url(String path) {
        String base = issuer();
        return base + (path.startsWith("/") ? path : "/" + path);
    }
}
