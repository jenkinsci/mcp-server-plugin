/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

public final class CallbackValidator {

    private static final Set<String> ALLOWED_HOSTS = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost");

    private CallbackValidator() {}

    public static URI validate(String callback) throws IllegalArgumentException {
        if (callback == null || callback.isEmpty()) {
            throw new IllegalArgumentException("callback is required");
        }

        URI uri;
        try {
            uri = new URI(callback);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("callback is not a valid URI");
        }

        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("callback scheme must be http");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("callback host is required");
        }
        if (!ALLOWED_HOSTS.contains(host.toLowerCase())) {
            throw new IllegalArgumentException("callback host must be loopback");
        }
        int port = uri.getPort();
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("callback port must be in [1024, 65535]");
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.contains("..")) {
            throw new IllegalArgumentException("callback path must be non-empty and safe");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("callback fragment is not allowed");
        }
        return uri;
    }
}
