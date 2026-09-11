/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.oauth;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.model.User;
import hudson.security.csrf.CrumbExclusion;
import io.jenkins.plugins.mcp.server.auth.IssuanceRecord;
import io.jenkins.plugins.mcp.server.auth.TokenLedger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.security.ApiTokenProperty;
import jenkins.security.apitoken.TokenUuidAndPlainValue;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

@Extension
@Slf4j
public class OAuthRegisterEndpoint implements UnprotectedRootAction {

    public static final String URL_NAME = "oauth";

    private static final Pattern REDIRECT_ARRAY = Pattern.compile("\"redirect_uris\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern NAME = Pattern.compile("\"client_name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

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

    // ---------- /oauth/register ----------
    public void doRegister(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        if (!OAuthConfig.isEnabled()) {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!"POST".equalsIgnoreCase(req.getMethod())) {
            JsonWriter.writeError(rsp, 405, "invalid_request", "POST only");
            return;
        }

        String body = readBody(req);
        String clientName = firstMatch(NAME, body, "mcp-client");
        List<String> redirectUris = extractRedirectUris(body);
        if (redirectUris.isEmpty()) {
            JsonWriter.writeError(rsp, 400, "invalid_redirect_uri", "redirect_uris is required");
            return;
        }
        for (String uri : redirectUris) {
            String err = validateRedirectUri(uri);
            if (err != null) {
                JsonWriter.writeError(rsp, 400, "invalid_redirect_uri", err);
                return;
            }
        }

        String clientId = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        ClientRegistration reg = new ClientRegistration(clientId, clientName, redirectUris, now);
        ClientRegistry.get().register(reg);

        log.info("MCP OAuth client registered: id={} name={} redirects={}", clientId, clientName, redirectUris);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_id", clientId);
        out.put("client_id_issued_at", now);
        out.put("client_name", clientName);
        out.put("redirect_uris", redirectUris);
        out.put("grant_types", List.of("authorization_code"));
        out.put("response_types", List.of("code"));
        out.put("token_endpoint_auth_method", "none");
        JsonWriter.writeJson(rsp, 201, out);
    }

    // ---------- /oauth/authorize ----------
    public void doAuthorize(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        String response_type = req.getParameter("response_type");
        String client_id = req.getParameter("client_id");
        String redirect_uri = req.getParameter("redirect_uri");
        String code_challenge = req.getParameter("code_challenge");
        String code_challenge_method = req.getParameter("code_challenge_method");
        String state = req.getParameter("state");
        String scope = req.getParameter("scope");

        if (!OAuthConfig.isEnabled()) {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        User user = User.current();
        if (user == null || "SYSTEM".equals(user.getId()) || "anonymous".equals(user.getId())) {
            String from = req.getRequestURIWithQueryString();
            rsp.sendRedirect(Jenkins.get().getRootUrl() + "login?from=" + enc(from));
            return;
        }

        if (!"code".equals(response_type)) {
            redirectError(rsp, redirect_uri, state, "unsupported_response_type", "only code supported");
            return;
        }

        ClientRegistration reg = ClientRegistry.get().find(client_id);
        if (reg == null) {
            rsp.sendError(400, "invalid_client");
            return;
        }
        if (!reg.allowsRedirectUri(redirect_uri)) {
            rsp.sendError(400, "invalid_redirect_uri");
            return;
        }
        if (!"S256".equals(code_challenge_method)) {
            redirectError(rsp, redirect_uri, state, "invalid_request", "code_challenge_method must be S256");
            return;
        }
        if (code_challenge == null || code_challenge.length() < 43 || code_challenge.length() > 128) {
            redirectError(rsp, redirect_uri, state, "invalid_request", "code_challenge invalid");
            return;
        }

        String codeValue = randomCode();
        long now = Instant.now().getEpochSecond();
        AuthorizationCode code = new AuthorizationCode(
                codeValue,
                client_id,
                user.getId(),
                redirect_uri,
                code_challenge,
                scope != null ? scope : "mcp",
                now + 60);
        AuthorizationCodeStore.get().put(code);
        reg.touch(now);

        log.info("MCP OAuth authorize: user={} client={} redirect={}", user.getId(), client_id, redirect_uri);

        String sep = redirect_uri.contains("?") ? "&" : "?";
        String location = redirect_uri + sep + "code=" + enc(codeValue) + (state != null ? "&state=" + enc(state) : "");
        rsp.sendRedirect(location);
    }

    // ---------- /oauth/token ----------
    public void doToken(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        String grant_type = req.getParameter("grant_type");
        String code = req.getParameter("code");
        String redirect_uri = req.getParameter("redirect_uri");
        String client_id = req.getParameter("client_id");
        String code_verifier = req.getParameter("code_verifier");

        if (!OAuthConfig.isEnabled()) {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!"POST".equalsIgnoreCase(req.getMethod())) {
            JsonWriter.writeError(rsp, 405, "invalid_request", "POST only");
            return;
        }
        if (!"authorization_code".equals(grant_type)) {
            JsonWriter.writeError(rsp, 400, "unsupported_grant_type", "only authorization_code");
            return;
        }
        if (code == null || client_id == null || redirect_uri == null || code_verifier == null) {
            JsonWriter.writeError(rsp, 400, "invalid_request", "missing params");
            return;
        }

        AuthorizationCode ac = AuthorizationCodeStore.get().consume(code);
        if (ac == null) {
            JsonWriter.writeError(rsp, 400, "invalid_grant", "code invalid or expired");
            return;
        }
        if (!ac.getClientId().equals(client_id)) {
            JsonWriter.writeError(rsp, 400, "invalid_grant", "client_id mismatch");
            return;
        }
        if (!ac.getRedirectUri().equals(redirect_uri)) {
            JsonWriter.writeError(rsp, 400, "invalid_grant", "redirect_uri mismatch");
            return;
        }
        if (!verifyPkce(code_verifier, ac.getCodeChallenge())) {
            JsonWriter.writeError(rsp, 400, "invalid_grant", "PKCE verification failed");
            return;
        }

        User user = User.getById(ac.getUserId(), false);
        if (user == null) {
            JsonWriter.writeError(rsp, 400, "invalid_grant", "user missing");
            return;
        }
        ApiTokenProperty prop = user.getProperty(ApiTokenProperty.class);
        if (prop == null) {
            prop = new ApiTokenProperty();
            try {
                user.addProperty(prop);
            } catch (IOException e) {
                JsonWriter.writeError(rsp, 500, "server_error", "cannot init api token property");
                return;
            }
        }

        ClientRegistration reg = ClientRegistry.get().find(client_id);
        String clientLabel = reg != null ? sanitize(reg.getClientName()) : "unknown";
        String tokenName = "mcp-oauth-" + clientLabel + "-" + UUID.randomUUID();
        TokenUuidAndPlainValue token = prop.getTokenStore().generateNewToken(tokenName);
        try {
            user.save();
        } catch (IOException e) {
            log.warn("Failed to save user after minting token", e);
        }

        long now = Instant.now().getEpochSecond();
        long ttl = OAuthConfig.defaultTtlSeconds();
        long expires = now + ttl;

        String remote = req.getRemoteAddr();
        TokenLedger.get()
                .put(new IssuanceRecord(token.tokenUuid, tokenName, user.getId(), now, expires, client_id, remote));

        log.info(
                "MCP OAuth token minted: user={} client={} tokenName={} ttl={}s",
                user.getId(),
                client_id,
                tokenName,
                ttl);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token.plainValue);
        body.put("token_type", "Bearer");
        body.put("expires_in", ttl);
        body.put("scope", ac.getScope());
        JsonWriter.writeJson(rsp, 200, body);
    }

    // ---------- helpers ----------
    private static String readBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = req.getReader()) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String firstMatch(Pattern p, String s, String fallback) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : fallback;
    }

    private static List<String> extractRedirectUris(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = REDIRECT_ARRAY.matcher(body);
        if (!m.find()) return out;
        String inside = m.group(1);
        Matcher s = STRING_LITERAL.matcher(inside);
        while (s.find()) out.add(s.group(1));
        return out;
    }

    private static String validateRedirectUri(String raw) {
        try {
            URI u = new URI(raw);
            String scheme = u.getScheme();
            String host = u.getHost();
            if (scheme == null) return "scheme missing";
            if ("http".equalsIgnoreCase(scheme)) {
                if (host == null) return "host missing";
                if (!("127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host))) {
                    return "http only allowed for loopback";
                }
            } else if (!"https".equalsIgnoreCase(scheme)) {
                return "only http (loopback) or https allowed";
            }
            if (u.getFragment() != null) return "fragment not allowed";
            return null;
        } catch (URISyntaxException e) {
            return "invalid URI: " + e.getMessage();
        }
    }

    private static void redirectError(StaplerResponse2 rsp, String redirectUri, String state, String err, String desc)
            throws IOException {
        if (redirectUri == null || redirectUri.isEmpty()) {
            rsp.sendError(400, err + ": " + desc);
            return;
        }
        String sep = redirectUri.contains("?") ? "&" : "?";
        String url = redirectUri + sep + "error=" + enc(err) + "&error_description=" + enc(desc)
                + (state != null ? "&state=" + enc(state) : "");
        rsp.sendRedirect(url);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String randomCode() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static boolean verifyPkce(String verifier, String challenge) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.US_ASCII), challenge.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Extension
    public static class Crumb extends CrumbExclusion {
        @Override
        public boolean process(HttpServletRequest req, HttpServletResponse rsp, FilterChain chain)
                throws IOException, ServletException {
            String uri = req.getRequestURI();
            if (uri != null
                    && (uri.endsWith("/oauth/register")
                            || uri.endsWith("/oauth/token")
                            || uri.endsWith("/oauth/revoke"))) {
                chain.doFilter(req, rsp);
                return true;
            }
            return false;
        }
    }
}
