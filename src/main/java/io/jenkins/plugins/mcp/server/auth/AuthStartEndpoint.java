/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.auth;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.RootAction;
import hudson.model.User;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.security.ApiTokenProperty;
import jenkins.security.apitoken.TokenUuidAndPlainValue;
import jenkins.util.SystemProperties;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

@Extension
@Slf4j
public class AuthStartEndpoint implements RootAction {

    public static final String URL_NAME = "mcp-server-auth";

    private static final String ENABLED_PROP = AuthStartEndpoint.class.getName() + ".enabled";
    private static final String DEFAULT_TTL_PROP = AuthStartEndpoint.class.getName() + ".defaultTtlSeconds";
    private static final String MAX_TTL_PROP = AuthStartEndpoint.class.getName() + ".maxTtlSeconds";

    private static final long DEFAULT_TTL_SECONDS = 28_800L;
    private static final long MIN_TTL_SECONDS = 300L;
    private static final long MAX_TTL_SECONDS = 86_400L;

    private static final Pattern STATE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    private static final Pattern LABEL_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,32}");

    private static final RateLimiter LIMITER = new RateLimiter(10, Duration.ofMinutes(1));

    @Override
    @CheckForNull
    public String getUrlName() {
        return URL_NAME;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return null;
    }

    @Override
    @CheckForNull
    public String getDisplayName() {
        return null;
    }

    public HttpResponse doStart(
            @QueryParameter String callback,
            @QueryParameter String state,
            @QueryParameter(required = false) Long ttl,
            @QueryParameter(required = false) String label)
            throws IOException {

        if (!SystemProperties.getBoolean(ENABLED_PROP, true)) {
            return HttpResponses.notFound();
        }

        Jenkins jenkins = Jenkins.get();

        User current = User.current();
        if (current == null || "SYSTEM".equals(current.getId()) || "anonymous".equals(current.getId())) {
            StaplerRequest2 loginReq = Stapler.getCurrentRequest2();
            String from = loginReq != null ? loginReq.getRequestURIWithQueryString() : "/" + URL_NAME + "/start";
            return HttpResponses.redirectTo(jenkins.getRootUrl() + "login?from=" + enc(from));
        }

        jenkins.checkPermission(Jenkins.READ);

        URI cbUri;
        try {
            cbUri = CallbackValidator.validate(callback);
        } catch (IllegalArgumentException e) {
            log.warn("Rejecting MCP auth start: {}", e.getMessage());
            return HttpResponses.errorWithoutStack(400, "invalid_callback: " + e.getMessage());
        }

        if (state == null || !STATE_PATTERN.matcher(state).matches()) {
            return HttpResponses.errorWithoutStack(400, "invalid_state");
        }

        if (label != null && !label.isEmpty() && !LABEL_PATTERN.matcher(label).matches()) {
            return HttpResponses.errorWithoutStack(400, "invalid_label");
        }

        long ttlSeconds = clampTtl(ttl);

        if (!LIMITER.tryAcquire(current.getId())) {
            log.warn("Rate limit hit for MCP auth start by user {}", current.getId());
            return HttpResponses.errorWithoutStack(429, "rate_limited");
        }

        ApiTokenProperty prop = current.getProperty(ApiTokenProperty.class);
        if (prop == null) {
            prop = new ApiTokenProperty();
            current.addProperty(prop);
        }

        String tokenName = "mcp-temp-" + UUID.randomUUID() + (label != null && !label.isEmpty() ? "-" + label : "");
        TokenUuidAndPlainValue token = prop.getTokenStore().generateNewToken(tokenName);
        current.save();

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);

        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String remoteAddr = clientAddr(req);

        TokenLedger.get()
                .put(new IssuanceRecord(
                        token.tokenUuid,
                        tokenName,
                        current.getId(),
                        now.getEpochSecond(),
                        expiresAt.getEpochSecond(),
                        label,
                        remoteAddr));

        log.info(
                "MCP temp token issued: user={} tokenName={} ttl={}s remote={}",
                current.getId(),
                tokenName,
                ttlSeconds,
                remoteAddr);

        String redirect = cbUri
                + (cbUri.getRawQuery() == null ? "?" : "&")
                + "token=" + enc(token.plainValue)
                + "&state=" + enc(state)
                + "&user=" + enc(current.getId())
                + "&expires=" + expiresAt.getEpochSecond();
        return HttpResponses.redirectTo(redirect);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static long clampTtl(Long requested) {
        long defaultTtl = SystemProperties.getLong(DEFAULT_TTL_PROP, DEFAULT_TTL_SECONDS);
        long maxTtl = SystemProperties.getLong(MAX_TTL_PROP, MAX_TTL_SECONDS);
        long ttl = requested != null ? requested : defaultTtl;
        if (ttl < MIN_TTL_SECONDS) {
            ttl = MIN_TTL_SECONDS;
        }
        if (ttl > maxTtl) {
            ttl = maxTtl;
        }
        return ttl;
    }

    private static String clientAddr(StaplerRequest2 req) {
        if (req == null) {
            return "unknown";
        }
        HttpServletRequest raw = req;
        String xff = raw.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            int comma = xff.indexOf(',');
            return comma == -1 ? xff.trim() : xff.substring(0, comma).trim();
        }
        return raw.getRemoteAddr();
    }
}
