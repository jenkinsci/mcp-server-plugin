/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Gong Yi.
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
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package io.jenkins.plugins.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.RootAction;
import hudson.model.User;
import hudson.security.csrf.CrumbExclusion;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.tool.McpToolWrapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import jenkins.util.HttpServletFilter;
import jenkins.util.SystemProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 *
 */
@Restricted(NoExternalUse.class)
@Extension
@Slf4j
public class Endpoint extends CrumbExclusion implements RootAction, HttpServletFilter {

    public static final String MCP_SERVER = "mcp-server";

    public static final String SSE_ENDPOINT = "/sse";

    public static final String MCP_SERVER_SSE = MCP_SERVER + SSE_ENDPOINT;
    public static final String STREAMABLE_ENDPOINT = "/mcp";

    public static final String MCP_SERVER_STREAMABLE = MCP_SERVER + STREAMABLE_ENDPOINT;

    /**
     * The endpoint path for handling client messages
     */
    private static final String MESSAGE_ENDPOINT = "/message";

    public static final String MCP_SERVER_MESSAGE = MCP_SERVER + MESSAGE_ENDPOINT;
    public static final String USER_ID = Endpoint.class.getName() + ".userId";
    public static final String HTTP_SERVLET_REQUEST = Endpoint.class.getName() + ".httpServletRequest";

    private static final String MCP_CONTEXT_KEY = Endpoint.class.getName() + ".mcpContext";

    /**
     * The interval in seconds for sending keep-alive messages to the client.
     * Default is 0 seconds (so disabled per default), can be overridden by setting the system property
     * it's not static final on purpose to allow dynamic configuration via script console.
     */
    private static int keepAliveInterval =
            SystemProperties.getInteger(Endpoint.class.getName() + ".keepAliveInterval", 0);

    /**
     * Whether to require the Origin header in requests. Default is false, can be overridden by setting the system
     * property {@code io.jenkins.plugins.mcp.server.Endpoint.requireOriginHeader=true}.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Accessible via System Groovy Scripts")
    public static boolean REQUIRE_ORIGIN_HEADER =
            SystemProperties.getBoolean(Endpoint.class.getName() + ".requireOriginHeader", false);

    /**
     *
     * Whether to require the Origin header to match the Jenkins root URL. Default is true, can be overridden by
     * setting the system property {@code io.jenkins.plugins.mcp.server.Endpoint.requireOriginMatch=false}.
     * The header will be validated only if present.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Accessible via System Groovy Scripts")
    public static boolean REQUIRE_ORIGIN_MATCH =
            SystemProperties.getBoolean(Endpoint.class.getName() + ".requireOriginMatch", true);

    /**
     * JSON object mapper for serialization/deserialization
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    HttpServletSseServerTransportProvider httpServletSseServerTransportProvider;
    HttpServletStreamableServerTransportProvider httpServletStreamableServerTransportProvider;

    public Endpoint() throws ServletException {
        init();
    }

    public static String getRequestedResourcePath(HttpServletRequest httpServletRequest) {
        return httpServletRequest
                .getRequestURI()
                .substring(httpServletRequest.getContextPath().length());
    }

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String requestedResource = getRequestedResourcePath(request);
        if (requestedResource.startsWith("/" + MCP_SERVER_MESSAGE)
                && request.getMethod().equalsIgnoreCase("POST")) {
            handleMessage(request, response, httpServletSseServerTransportProvider);
            return true; // Do not allow this request on to Stapler
        }
        if (requestedResource.startsWith("/" + MCP_SERVER_SSE)
                && request.getMethod().equalsIgnoreCase("POST")) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return true;
        }
        if (isStreamableRequest(request)) {
            handleMessage(request, response, httpServletStreamableServerTransportProvider);
            return true;
        }
        // Return 404 for OAuth 2.1 endpoints that this plugin doesn't implement.
        // This tells MCP clients that OAuth DCR is not supported, so they should
        // fall back to using the Authorization header (Basic Auth with API token).
        if (isOAuthEndpoint(requestedResource)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json");
            response.getWriter()
                    .write(
                            "{\"error\":\"not_found\",\"error_description\":\"OAuth endpoints not implemented. Use Basic Auth with Jenkins API token.\"}");
            response.getWriter().flush();
            return true;
        }
        return false;
    }

    /**
     * Check if the request is for an OAuth 2.1 related endpoint.
     * Jenkins MCP server uses Basic Auth with API tokens, not OAuth 2.1.
     */
    private boolean isOAuthEndpoint(String requestedResource) {
        return requestedResource.equals("/register")
                || requestedResource.startsWith("/.well-known/oauth")
                || requestedResource.startsWith("/.well-known/openid");
    }

    protected void init() throws ServletException {

        McpSchema.ServerCapabilities serverCapabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .prompts(true)
                .resources(true, true)
                .build();
        var extensions = McpServerExtension.all();

        var tools = extensions.stream()
                .map(McpServerExtension::getSyncTools)
                .flatMap(List::stream)
                .toList();
        var prompts = extensions.stream()
                .map(McpServerExtension::getSyncPrompts)
                .flatMap(List::stream)
                .toList();
        var resources = extensions.stream()
                .map(McpServerExtension::getSyncResources)
                .flatMap(List::stream)
                .toList();

        var annotationTools = extensions.stream()
                .flatMap(extension -> Arrays.stream(extension.getClass().getMethods())
                        .filter(method -> method.isAnnotationPresent(Tool.class))
                        .map(method -> new McpToolWrapper(objectMapper, extension, method).asSyncToolSpecification()))
                .toList();

        List<McpServerFeatures.SyncToolSpecification> allTools = new ArrayList<>();
        allTools.addAll(tools);
        allTools.addAll(annotationTools);

        var rootUrl = jenkins.model.JenkinsLocationConfiguration.get().getUrl();
        if (rootUrl == null) {
            rootUrl = "";
        }
        httpServletSseServerTransportProvider = HttpServletSseServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .baseUrl(rootUrl)
                .sseEndpoint(SSE_ENDPOINT)
                .messageEndpoint(MCP_SERVER_MESSAGE)
                .contextExtractor(createExtractor())
                .keepAliveInterval(keepAliveInterval > 0 ? Duration.ofSeconds(keepAliveInterval) : null)
                .build();

        io.modelcontextprotocol.server.McpServer.sync(httpServletSseServerTransportProvider)
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .jsonSchemaValidator(new DefaultJsonSchemaValidator(objectMapper))
                .capabilities(serverCapabilities)
                .tools(allTools)
                .prompts(prompts)
                .resources(resources)
                .build();

        httpServletStreamableServerTransportProvider = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint(STREAMABLE_ENDPOINT)
                .contextExtractor(createExtractor())
                .keepAliveInterval(keepAliveInterval > 0 ? Duration.ofSeconds(keepAliveInterval) : null)
                .build();

        io.modelcontextprotocol.server.McpServer.sync(httpServletStreamableServerTransportProvider)
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .jsonSchemaValidator(new DefaultJsonSchemaValidator(objectMapper))
                .capabilities(serverCapabilities)
                .tools(allTools)
                .prompts(prompts)
                .resources(resources)
                .build();
    }

    @Override
    public boolean handle(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        if (isSSERequest(req)) {
            handleSSE(req, resp);
            return true;
        } else if (isStreamableRequest(req)) {
            if (isBrowserRequest(req)) {
                // Serve friendly error page for GET requests to /mcp-server/mcp
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.setContentType("text/html;charset=UTF-8");
                resp.getWriter()
                        .write(
                                "<html><head><title>Model Context Protocol Endpoint</title></head>"
                                        + "<body><h2>This endpoint is designed for an AI agent using the Model Context Protocol.</h2></body></html>");
                resp.getWriter().flush();
            } else {
                handleMessage(req, resp, httpServletStreamableServerTransportProvider);
            }
            return true;
        } else {
            return false;
        }
    }

    private boolean isBrowserRequest(HttpServletRequest req) {
        if (req.getMethod().equalsIgnoreCase("GET")) {
            String acceptHeader = req.getHeader("Accept");
            return acceptHeader != null && acceptHeader.contains("text/html");
        } else {
            return false;
        }
    }

    private static McpTransportContextExtractor<HttpServletRequest> createExtractor() {
        return (httpServletRequest) -> (McpTransportContext) httpServletRequest.getAttribute(MCP_CONTEXT_KEY);
    }

    private boolean validOriginHeader(HttpServletRequest request, HttpServletResponse response) {
        String originHeaderValue = request.getHeader("Origin");
        if (REQUIRE_ORIGIN_HEADER && StringUtils.isEmpty(originHeaderValue)) {
            try {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Missing Origin header");
                return false;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (REQUIRE_ORIGIN_MATCH && !StringUtils.isEmpty(originHeaderValue)) {
            var jenkinsRootUrl =
                    jenkins.model.JenkinsLocationConfiguration.get().getUrl();
            if (StringUtils.isEmpty(jenkinsRootUrl)) {
                // If Jenkins root URL is not configured, we cannot validate the Origin header
                return true;
            }

            String o = getRootUrlFromRequest(request);
            String removeSuffix1 = "/";
            if (o.endsWith(removeSuffix1)) {
                o = o.substring(0, o.length() - removeSuffix1.length());
            }
            String removeSuffix2 = request.getContextPath();
            if (o.endsWith(removeSuffix2)) {
                o = o.substring(0, o.length() - removeSuffix2.length());
            }
            final String expectedOrigin = o;

            if (!originHeaderValue.equals(expectedOrigin)) {
                log.debug("Rejecting origin: {}; expected was from request: {}", originHeaderValue, expectedOrigin);
                try {

                    response.sendError(
                            HttpServletResponse.SC_FORBIDDEN,
                            "Unexpected request origin (check your reverse proxy settings)");
                    return false;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return true;
    }

    /**
     * Horrible copy/paste from {@link Jenkins} but this method in Jenkins is so dependent of Stapler#currentRequest
     * that it's not possible to call it from here.
     */
    private @NonNull String getRootUrlFromRequest(HttpServletRequest req) {

        StringBuilder buf = new StringBuilder();
        String scheme = getXForwardedHeader(req, "X-Forwarded-Proto", req.getScheme());
        buf.append(scheme).append("://");
        String host = getXForwardedHeader(req, "X-Forwarded-Host", req.getServerName());
        int index = host.lastIndexOf(':');
        int port = req.getServerPort();
        if (index == -1) {
            // Almost everyone else except Nginx put the host and port in separate headers
            buf.append(host);
        } else {
            if (host.startsWith("[") && host.endsWith("]")) {
                // support IPv6 address
                buf.append(host);
            } else {
                // Nginx uses the same spec as for the Host header, i.e. hostname:port
                buf.append(host, 0, index);
                if (index + 1 < host.length()) {
                    try {
                        port = Integer.parseInt(host.substring(index + 1));
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
                // but if a user has configured Nginx with an X-Forwarded-Port, that will win out.
            }
        }
        String forwardedPort = getXForwardedHeader(req, "X-Forwarded-Port", null);
        if (forwardedPort != null) {
            try {
                port = Integer.parseInt(forwardedPort);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        if (port != ("https".equals(scheme) ? 443 : 80)) {
            buf.append(':').append(port);
        }
        buf.append(req.getContextPath()).append('/');
        return buf.toString();
    }

    private static String getXForwardedHeader(HttpServletRequest req, String header, String defaultValue) {
        String value = req.getHeader(header);
        if (value != null) {
            int index = value.indexOf(',');
            return index == -1 ? value.trim() : value.substring(0, index).trim();
        }
        return defaultValue;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return MCP_SERVER;
    }

    private boolean isSSERequest(HttpServletRequest request) {
        String requestedResource = getRequestedResourcePath(request);
        return requestedResource.startsWith("/" + MCP_SERVER_SSE)
                && request.getMethod().equalsIgnoreCase("GET");
    }

    private boolean isStreamableRequest(HttpServletRequest request) {
        String requestedResource = getRequestedResourcePath(request);
        return requestedResource.startsWith("/" + MCP_SERVER_STREAMABLE)
                && (request.getMethod().equalsIgnoreCase("GET")
                        || (request.getMethod().equalsIgnoreCase("POST")));
    }

    private void handleSSE(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        httpServletSseServerTransportProvider.service(request, response);
    }

    private void handleMessage(HttpServletRequest request, HttpServletResponse response, HttpServlet httpServlet)
            throws IOException, ServletException {
        if (!validOriginHeader(request, response)) {
            return;
        }
        prepareMcpContext(request);
        httpServlet.service(request, response);
    }

    private static void prepareMcpContext(HttpServletRequest request) {
        Map<String, Object> contextMap = new HashMap<>();
        var currentUser = User.current();
        String userId = null;
        if (currentUser != null) {
            userId = currentUser.getId();
        }
        if (userId != null) {
            contextMap.put(USER_ID, userId);
        }
        contextMap.put(HTTP_SERVLET_REQUEST, request);
        request.setAttribute(MCP_CONTEXT_KEY, McpTransportContext.create(contextMap));
    }
}
