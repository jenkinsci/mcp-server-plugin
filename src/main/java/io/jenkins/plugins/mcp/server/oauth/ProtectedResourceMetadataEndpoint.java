/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.oauth;

import hudson.Extension;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.util.HttpServletFilter;
import lombok.extern.slf4j.Slf4j;

@Extension(ordinal = Integer.MAX_VALUE)
@Slf4j
public class ProtectedResourceMetadataEndpoint implements HttpServletFilter {

    @Override
    public boolean handle(HttpServletRequest req, HttpServletResponse rsp) throws IOException, ServletException {
        String uri = req.getRequestURI();
        if (uri == null) return false;

        log.debug("ProtectedResourceMetadataEndpoint saw uri={}", uri);

        boolean protectedResource =
                uri.endsWith("/.well-known/oauth-protected-resource") || uri.endsWith("/wk/oauth-protected-resource");
        boolean authServer = uri.endsWith("/.well-known/oauth-authorization-server")
                || uri.endsWith("/wk/oauth-authorization-server");

        if (!protectedResource && !authServer) return false;

        if (!OAuthConfig.isEnabled()) {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return true;
        }

        if (protectedResource) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("resource", OAuthConfig.url("/mcp-server"));
            body.put("authorization_servers", List.of(OAuthConfig.issuer()));
            body.put("bearer_methods_supported", List.of("header"));
            body.put("scopes_supported", List.of("mcp"));
            body.put("resource_documentation", "https://plugins.jenkins.io/mcp-server/");
            JsonWriter.writeJson(rsp, 200, body);
            return true;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", OAuthConfig.issuer());
        body.put("authorization_endpoint", OAuthConfig.url("/oauth/authorize"));
        body.put("token_endpoint", OAuthConfig.url("/oauth/token"));
        body.put("registration_endpoint", OAuthConfig.url("/oauth/register"));
        body.put("revocation_endpoint", OAuthConfig.url("/oauth/revoke"));
        body.put("response_types_supported", List.of("code"));
        body.put("grant_types_supported", List.of("authorization_code"));
        body.put("code_challenge_methods_supported", List.of("S256"));
        body.put("token_endpoint_auth_methods_supported", List.of("none"));
        body.put("scopes_supported", List.of("mcp"));
        JsonWriter.writeJson(rsp, 200, body);
        return true;
    }
}
