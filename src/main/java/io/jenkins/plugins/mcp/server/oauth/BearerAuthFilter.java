/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.oauth;

import hudson.Extension;
import hudson.model.User;
import hudson.security.csrf.CrumbExclusion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import jenkins.security.ApiTokenProperty;
import jenkins.util.HttpServletFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Authenticates MCP requests carrying {@code Authorization: Bearer <api-token>}.
 *
 * <p>Registered both as a {@link CrumbExclusion} and as an {@link HttpServletFilter}: the MCP
 * {@code Endpoint} handles POST requests inside {@link CrumbExclusion#process}, which runs in the
 * CSRF filter before any {@link HttpServletFilter}. The crumb-exclusion hook therefore covers POST,
 * and the servlet filter covers GET (SSE / streamable listeners).
 */
@Extension(ordinal = Integer.MAX_VALUE)
@Slf4j
public class BearerAuthFilter extends CrumbExclusion implements HttpServletFilter {

    @Override
    public boolean process(HttpServletRequest req, HttpServletResponse rsp, FilterChain chain)
            throws IOException, ServletException {
        return apply(req, rsp);
    }

    @Override
    public boolean handle(HttpServletRequest req, HttpServletResponse rsp) throws IOException, ServletException {
        return apply(req, rsp);
    }

    /**
     * @return {@code true} if the response was fully written (invalid bearer), {@code false} to continue.
     */
    static boolean apply(HttpServletRequest req, HttpServletResponse rsp) throws IOException {
        String path = req.getRequestURI();
        if (path == null) return false;
        String context = req.getContextPath();
        String rel = path.startsWith(context) ? path.substring(context.length()) : path;
        if (!rel.startsWith("/mcp-server") && !rel.startsWith("/mcp-health")) {
            return false;
        }

        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        String bearer = auth.substring(7).trim();
        if (bearer.isEmpty()) return false;

        // Already resolved earlier in the chain (e.g. CrumbExclusion then HttpServletFilter).
        if (req.getAttribute(BearerAuthFilter.class.getName()) != null) {
            return false;
        }

        User match = findUserByToken(bearer);
        if (match == null) {
            UnauthorizedChallengeFilter.challenge(rsp, "invalid_token");
            return true;
        }

        SecurityContextHolder.getContext().setAuthentication(match.impersonate2());
        req.setAttribute(BearerAuthFilter.class.getName(), match.getId());
        return false;
    }

    private static User findUserByToken(String plainToken) {
        for (User u : User.getAll()) {
            ApiTokenProperty prop = u.getProperty(ApiTokenProperty.class);
            if (prop == null) continue;
            try {
                if (prop.matchesPassword(plainToken)) return u;
            } catch (RuntimeException e) {
                log.warn("Bearer token match failed for user {}", u.getId(), e);
            }
        }
        return null;
    }
}
