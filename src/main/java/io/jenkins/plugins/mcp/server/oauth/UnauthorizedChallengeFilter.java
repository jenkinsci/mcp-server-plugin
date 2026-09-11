/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.oauth;

import hudson.Extension;
import hudson.security.csrf.CrumbExclusion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import jenkins.model.Jenkins;
import jenkins.util.HttpServletFilter;

/**
 * Replies 401 with an RFC 9728 {@code WWW-Authenticate} challenge to unauthenticated MCP requests.
 *
 * <p>See {@link BearerAuthFilter} for why this is both a {@link CrumbExclusion} and an
 * {@link HttpServletFilter}. Must run after {@link BearerAuthFilter} (lower ordinal).
 */
@Extension(ordinal = Integer.MAX_VALUE - 1)
public class UnauthorizedChallengeFilter extends CrumbExclusion implements HttpServletFilter {

    @Override
    public boolean process(HttpServletRequest req, HttpServletResponse rsp, FilterChain chain)
            throws IOException, ServletException {
        return apply(req, rsp);
    }

    @Override
    public boolean handle(HttpServletRequest req, HttpServletResponse rsp) throws IOException, ServletException {
        return apply(req, rsp);
    }

    static boolean apply(HttpServletRequest req, HttpServletResponse rsp) throws IOException {
        String path = req.getRequestURI();
        if (path == null) return false;
        String context = req.getContextPath();
        String rel = path.startsWith(context) ? path.substring(context.length()) : path;
        if (!rel.startsWith("/mcp-server/")) return false;

        String header = req.getHeader("Authorization");
        if (header != null && !header.isBlank()) return false;

        var current = Jenkins.getAuthentication2();
        boolean anon = current == null || "anonymous".equals(current.getName()) || !current.isAuthenticated();
        if (!anon) return false;

        challenge(rsp, "invalid_token");
        return true;
    }

    static void challenge(HttpServletResponse rsp, String error) throws IOException {
        String resource = OAuthConfig.url("/.well-known/oauth-protected-resource");
        rsp.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"" + resource + "\", error=\"" + error + "\"");
        rsp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        rsp.setContentType("application/json; charset=utf-8");
        rsp.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
